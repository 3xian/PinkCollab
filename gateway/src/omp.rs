use crate::storage::id;
use anyhow::{Context, Result, bail, ensure};
use parking_lot::Mutex;
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    io::{BufRead, BufReader, Write},
    path::Path,
    process::{Child, Command, ExitStatus, Stdio},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::process::Command as TokioCommand;
use tokio::sync::{mpsc, oneshot, watch};

/// Framing limit for a single NDJSON frame in either direction. This is PinkCollab's own bound:
/// an OMP frame that exceeds it ends the session instead of being buffered without limit.
const MAX_LINE: usize = 1024 * 1024;

/// Upper bound for one blocking stdin write. Pipe buffers are small (64 KiB on Windows) while a
/// prompt may be 256 KiB, so a wedged OMP must fail the transport instead of stalling it.
const WRITE_TIMEOUT: Duration = Duration::from_secs(10);

/// How long OMP may take to exit after its stdin closes before it is killed.
const TERMINATE_GRACE: Duration = Duration::from_secs(3);
/// One bounded `omp --version` probe; `unavailable` covers a missing binary and any failure.
pub async fn version(executable: &str) -> String {
    tokio::time::timeout(
        Duration::from_secs(5),
        TokioCommand::new(executable)
            .kill_on_drop(true)
            .arg("--version")
            .output(),
    )
    .await
    .ok()
    .and_then(Result::ok)
    .filter(|output| output.status.success())
    .map(|output| String::from_utf8_lossy(&output.stdout).trim().to_owned())
    .unwrap_or_else(|| "unavailable".into())
}

pub enum Output {
    Frame(Value),
    /// The process is gone. `Some` carries why the transport failed, `None` means it exited
    /// normally or because we asked it to.
    Exited(Option<String>),
}

/// Sole owner of the child process. Termination lives here, not in the write path, so neither a
/// blocked stdin write nor a stalled event consumer can keep a stop from taking effect.
struct Process {
    child: Mutex<Child>,
    #[cfg(windows)]
    job: crate::windows_job::Job,
}

impl Process {
    /// Concurrent callers serialize on the child handle and observe the same reaped exit status.
    /// A failed kill or wait is an error, never evidence that the process has exited.
    fn terminate(&self, grace: Duration) -> Result<ExitStatus> {
        let mut child = self.child.lock();
        let deadline = Instant::now() + grace;
        loop {
            if let Some(status) = child.try_wait().context("cannot inspect OMP process")? {
                #[cfg(not(windows))]
                return Ok(status);
                #[cfg(windows)]
                if self.job.empty()? {
                    return Ok(status);
                }
            }
            if Instant::now() >= deadline {
                break;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        #[cfg(windows)]
        {
            self.job.terminate_and_wait()?;
            child.wait().context("cannot reap OMP process")
        }
        #[cfg(not(windows))]
        {
            if let Err(err) = child.kill() {
                // The child may have exited between the final poll and the kill request.
                if let Some(status) = child
                    .try_wait()
                    .context("cannot inspect OMP after kill failed")?
                {
                    return Ok(status);
                }
                return Err(err).context("cannot kill OMP process");
            }
            child.wait().context("cannot reap OMP process")
        }
    }
}

/// Single termination point shared by the reader thread, the writer thread and [`Runtime::stop`].
struct Shutdown {
    process: Arc<Process>,
    pending: Arc<Mutex<HashMap<String, oneshot::Sender<Value>>>>,
    requested: watch::Sender<bool>,
    exited: watch::Sender<bool>,
    output: mpsc::Sender<Output>,
    finished: AtomicBool,
}

impl Shutdown {
    /// Idempotent: ends the process, releases every pending request and reports the outcome once.
    fn finish(&self, reason: Option<String>) {
        if self.finished.load(Ordering::SeqCst) {
            return;
        }
        let status = match self.process.terminate(TERMINATE_GRACE) {
            Ok(status) => status,
            Err(err) => {
                eprintln!("Could not confirm OMP exit: {err:#}");
                return;
            }
        };
        if self.finished.swap(true, Ordering::SeqCst) {
            return;
        }
        // A stop we requested is not a crash; `session::exit` already reports it as stopped.
        let reason = reason.or_else(|| {
            (!*self.requested.borrow() && !status.success())
                .then(|| "OMP process exited unexpectedly".to_string())
        });
        self.pending.lock().clear();
        let _ = self.exited.send(true);
        let _ = self.output.blocking_send(Output::Exited(reason));
    }
}

pub struct Runtime {
    input: mpsc::Sender<(Value, oneshot::Sender<Result<()>>)>,
    pending: Arc<Mutex<HashMap<String, oneshot::Sender<Value>>>>,
    ready: watch::Receiver<bool>,
    exited: watch::Receiver<bool>,
    stop: watch::Sender<bool>,
    process: Arc<Process>,
}

impl Runtime {
    pub fn spawn(
        executable: &str,
        args: &[String],
        cwd: &Path,
    ) -> Result<(Arc<Self>, mpsc::Receiver<Output>)> {
        let mut child = Command::new(executable)
            // `Stdio::piped()` is the only pipe path either `std` or tokio offers here: tokio's
            // process spawn extracts the very same `CreatePipe` handles `std` created, so an
            // explicit `CreatePipe` wrapper (removed in favour of this) would change nothing
            // about how OMP's pipes are allocated. ERROR_PIPE_BUSY (231) is unrelated to that
            // choice and stays an open observation on saturated pipe namespaces.
            .args(["--mode", "rpc-ui"])
            .args(args)
            .current_dir(cwd)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .with_context(|| format!("cannot start OMP at {executable} in {}", cwd.display()))?;
        #[cfg(windows)]
        let job = match crate::windows_job::Job::attach(&child) {
            Ok(job) => job,
            Err(err) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(err);
            }
        };
        let stdin = child.stdin.take().context("OMP stdin unavailable")?;
        let stdout = child.stdout.take().context("OMP stdout unavailable")?;
        let (input, mut commands) = mpsc::channel::<(Value, oneshot::Sender<Result<()>>)>(32);
        // A frame is at most 1 MiB; this caps queued stdout at roughly 32 MiB.
        let (output, receiver) = mpsc::channel(32);
        let (ready_tx, ready) = watch::channel(false);
        let (exit_tx, exited) = watch::channel(false);
        let (stop, mut stopping) = watch::channel(false);
        let pending = Arc::new(Mutex::new(HashMap::<String, oneshot::Sender<Value>>::new()));
        let process = Arc::new(Process {
            child: Mutex::new(child),
            #[cfg(windows)]
            job,
        });
        let shutdown = Arc::new(Shutdown {
            process: process.clone(),
            pending: pending.clone(),
            requested: stop.clone(),
            exited: exit_tx,
            output: output.clone(),
            finished: AtomicBool::new(false),
        });
        let reader = shutdown.clone();
        let reader_output = output.clone();
        let reader_pending = pending.clone();
        std::thread::spawn(move || {
            let mut source = BufReader::new(stdout);
            let mut frame = Vec::with_capacity(8 * 1024);
            let reason = loop {
                frame.clear();
                match read_frame(&mut source, &mut frame) {
                    // stdout closed: OMP is exiting, either on its own or because we stopped it.
                    Ok(0) => break None,
                    Ok(_) if frame.len() > MAX_LINE => {
                        break Some("OMP exceeded the NDJSON transport limit".to_string());
                    }
                    Ok(_) => {}
                    Err(err) => break Some(format!("OMP output transport failed: {err}")),
                }
                let Ok(value) = serde_json::from_slice::<Value>(&frame) else {
                    continue;
                };
                if string(&value, "type") == "ready" {
                    let _ = ready_tx.send(true);
                }
                if string(&value, "type") == "response"
                    && let Some(responder) = reader_pending.lock().remove(string(&value, "id"))
                {
                    let _ = responder.send(value);
                    continue;
                }
                if reader_output.blocking_send(Output::Frame(value)).is_err() {
                    break None;
                }
            };
            reader.finish(reason);
        });
        let writer = shutdown.clone();
        // Writes are blocking, so they run on a dedicated thread and never on a runtime worker.
        // The handle must be resolved here, on the runtime thread: calling `Handle::current()`
        // inside the spawned thread panics and silently kills the writer.
        let runtime = tokio::runtime::Handle::current();
        std::thread::spawn(move || {
            let reason: Option<String> = runtime.block_on(async move {
                let stdin: Arc<Mutex<Option<Box<dyn Write + Send>>>> =
                    Arc::new(Mutex::new(Some(Box::new(stdin))));
                let reason = loop {
                    let command = tokio::select! {
                        _ = stopping.changed() => None,
                        command = commands.recv() => command,
                    };
                    let Some((value, ack)) = command else {
                        break None;
                    };
                    let result = write_frame(&stdin, value).await;
                    let failure = result
                        .as_ref()
                        .err()
                        .map(|err| format!("OMP input transport failed: {err:#}"));
                    let _ = ack.send(result);
                    if failure.is_some() {
                        break failure;
                    }
                };
                // Releasing the last stdin handle asks OMP to exit on its own.
                let _ = stdin.lock().take();
                reason
            });
            writer.finish(reason);
        });
        Ok((
            Arc::new(Self {
                input,
                pending,
                ready,
                exited,
                stop,
                process,
            }),
            receiver,
        ))
    }

    pub async fn wait_ready(&self) -> Result<()> {
        let mut ready = self.ready.clone();
        let mut exited = self.exited.clone();
        tokio::time::timeout(Duration::from_secs(30), async {
            loop {
                if *ready.borrow() {
                    return Ok(());
                }
                if *exited.borrow() {
                    bail!("OMP exited before ready");
                }
                tokio::select! {
                    result = ready.changed() => { result.context("OMP ready channel closed")?; },
                    _ = exited.changed() => {},
                }
            }
        })
        .await
        .context("OMP startup timed out")?
    }

    pub async fn write(&self, frame: Value) -> Result<()> {
        let (ack, rx) = oneshot::channel();
        self.input
            .send((frame, ack))
            .await
            .context("OMP no longer attached")?;
        rx.await.context("OMP input closed")?
    }

    pub async fn request(&self, frame: Value) -> Result<Value> {
        self.request_with_id(id("req_"), frame).await
    }

    /// A caller-supplied id lets the session controller correlate a later `prompt_result` with
    /// the durable command receipt. OMP echoes this id in the immediate response and terminal
    /// result; it is scoped to this process and never accepted from a client as a raw RPC frame.
    pub async fn request_with_id(&self, request_id: String, mut frame: Value) -> Result<Value> {
        frame["id"] = json!(request_id);
        let (tx, rx) = oneshot::channel();
        self.pending.lock().insert(request_id.clone(), tx);
        let result = async {
            self.write(frame).await?;
            let response = tokio::time::timeout(Duration::from_secs(15), rx)
                .await
                .context("OMP response timed out")?
                .context("OMP exited before response")?;
            ensure!(
                response["success"] == true,
                "OMP rejected command: {}",
                string(&response, "error")
            );
            Ok(response)
        }
        .await;
        self.pending.lock().remove(&request_id);
        result
    }

    /// Ends the OMP process. Bounded by [`TERMINATE_GRACE`]: termination is owned by [`Process`],
    /// so it works even while a write is blocked or a client has stopped draining events.
    pub async fn stop_confirmed(&self) -> Result<()> {
        let _ = self.stop.send(true);
        let process = self.process.clone();
        tokio::time::timeout(
            Duration::from_secs(12),
            tokio::task::spawn_blocking(move || process.terminate(TERMINATE_GRACE)),
        )
        .await
        .context("OMP termination did not finish")?
        .context("OMP terminator failed")??;
        let mut exited = self.exited.clone();
        tokio::time::timeout(Duration::from_secs(12), async {
            while !*exited.borrow() {
                exited
                    .changed()
                    .await
                    .context("OMP exit observation closed")?;
            }
            Ok::<(), anyhow::Error>(())
        })
        .await
        .context("OMP exit was not confirmed")??;
        Ok(())
    }

    pub async fn stop(&self) {
        let _ = self.stop_confirmed().await;
    }

    pub fn alive(&self) -> bool {
        !*self.exited.borrow()
    }
}

impl Drop for Runtime {
    /// Takes the place of tokio's `kill_on_drop`: the last handle must not leave an orphaned OMP.
    fn drop(&mut self) {
        let _ = self.stop.send(true);
        if let Err(err) = self.process.terminate(Duration::ZERO) {
            eprintln!("Could not confirm OMP exit during drop: {err:#}");
        }
    }
}

/// Reads one NDJSON frame without ever buffering more than `MAX_LINE + 1` bytes, so a line that
/// never ends fails the transport instead of growing until the Gateway runs out of memory.
fn read_frame<R: BufRead>(source: &mut R, frame: &mut Vec<u8>) -> std::io::Result<usize> {
    let mut bounded = std::io::Read::take(&mut *source, (MAX_LINE + 1) as u64);
    bounded.read_until(b'\n', frame)
}

async fn write_frame(pipe: &Arc<Mutex<Option<Box<dyn Write + Send>>>>, value: Value) -> Result<()> {
    let mut bytes = serde_json::to_vec(&value)?;
    ensure!(bytes.len() < MAX_LINE, "OMP frame too large");
    bytes.push(b'\n');
    let pipe = pipe.clone();
    let write = tokio::task::spawn_blocking(move || -> Result<()> {
        let mut guard = pipe.lock();
        let stream = guard.as_mut().context("OMP stdin is closed")?;
        stream.write_all(&bytes)?;
        stream.flush()?;
        Ok(())
    });
    match tokio::time::timeout(WRITE_TIMEOUT, write).await {
        Ok(Ok(result)) => result,
        Ok(Err(join)) => Err(join).context("OMP write task failed"),
        Err(_) => bail!("OMP write timed out after {WRITE_TIMEOUT:?}"),
    }
}

pub fn string<'a>(frame: &'a Value, key: &str) -> &'a str {
    frame.get(key).and_then(Value::as_str).unwrap_or("")
}

pub fn text_content(message: &Value) -> String {
    message["content"]
        .as_array()
        .map(|items| {
            items
                .iter()
                .filter(|v| v["type"] == "text")
                .map(|v| string(v, "text"))
                .collect::<Vec<_>>()
                .join("\n")
        })
        .unwrap_or_else(|| string(message, "content").to_owned())
}
