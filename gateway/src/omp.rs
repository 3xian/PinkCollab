use crate::storage::id;
use anyhow::{Context, Result, bail, ensure};
use futures_util::StreamExt;
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    path::Path,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::{
    io::AsyncWriteExt,
    process::Command,
    sync::{mpsc, oneshot, watch},
};
use tokio_util::codec::{FramedRead, LinesCodec};

pub enum Output {
    Frame(Value),
    Exited(Option<String>),
}
pub struct Runtime {
    input: mpsc::Sender<(Value, oneshot::Sender<Result<()>>)>,
    pending: Arc<Mutex<HashMap<String, oneshot::Sender<Value>>>>,
    ready: watch::Receiver<bool>,
    exited: watch::Receiver<bool>,
    stop: watch::Sender<bool>,
}
impl Runtime {
    pub fn spawn(
        executable: &str,
        args: &[String],
        cwd: &Path,
    ) -> Result<(Arc<Self>, mpsc::Receiver<Output>)> {
        let mut child = Command::new(executable)
            .args(["--mode", "rpc-ui"])
            .args(args)
            .current_dir(cwd)
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null())
            .kill_on_drop(true)
            .spawn()
            .context("cannot start OMP")?;
        let mut stdin = child.stdin.take().context("OMP stdin unavailable")?;
        let mut stdout = FramedRead::new(
            child.stdout.take().context("OMP stdout unavailable")?,
            LinesCodec::new_with_max_length(1024 * 1024),
        );
        let (input, mut commands) = mpsc::channel::<(Value, oneshot::Sender<Result<()>>)>(32);
        let (output, receiver) = mpsc::channel(256);
        let (ready_tx, ready) = watch::channel(false);
        let (exit_tx, exited) = watch::channel(false);
        let (stop, mut stopping) = watch::channel(false);
        let pending = Arc::new(Mutex::new(HashMap::<String, oneshot::Sender<Value>>::new()));
        let responses = pending.clone();
        tokio::spawn(async move {
            let mut error = None;
            loop {
                tokio::select! {
                    _ = stopping.changed() => {
                        let _ = stdin.shutdown().await;
                        drop(stdin);
                        if tokio::time::timeout(Duration::from_secs(3), child.wait()).await.is_err() {
                            let _ = child.kill().await;
                        }
                        break;
                    }
                    command = commands.recv() => {
                        let Some((value, ack)) = command else {
                            let _ = child.kill().await;
                            break;
                        };
                        let result = async {
                            let mut bytes = serde_json::to_vec(&value)?;
                            ensure!(bytes.len() < 1024 * 1024, "OMP frame too large");
                            bytes.push(b'\n');
                            tokio::time::timeout(Duration::from_secs(10), stdin.write_all(&bytes))
                                .await.context("OMP write timed out")??;
                            Ok(())
                        }.await;
                        let failed = result.is_err();
                        let _ = ack.send(result);
                        if failed {
                            error = Some("OMP input transport failed".into());
                            let _ = child.kill().await;
                            break;
                        }
                    }
                    line = stdout.next() => {
                        match line {
                            Some(Ok(line)) => {
                                let Ok(frame) = serde_json::from_str::<Value>(&line) else { continue; };
                                if string(&frame, "type") == "ready" { let _ = ready_tx.send(true); }
                                if string(&frame, "type") == "response" {
                                    let responder = responses.lock().unwrap().remove(string(&frame, "id"));
                                    if let Some(tx) = responder { let _ = tx.send(frame); continue; }
                                }
                                if output.send(Output::Frame(frame)).await.is_err() {
                                    let _ = child.kill().await;
                                    break;
                                }
                            }
                            Some(Err(_)) => {
                                error = Some("OMP exceeded NDJSON transport limit".into());
                                let _ = child.kill().await;
                                break;
                            }
                            None => {
                                match child.wait().await {
                                    Ok(status) if status.success() => {},
                                    _ => error = Some("OMP process exited unexpectedly".into()),
                                }
                                break;
                            }
                        }
                    }
                }
            }
            responses.lock().unwrap().clear();
            let _ = exit_tx.send(true);
            let _ = output.send(Output::Exited(error)).await;
        });
        Ok((
            Arc::new(Self {
                input,
                pending,
                ready,
                exited,
                stop,
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
    pub async fn request(&self, mut frame: Value) -> Result<Value> {
        let request_id = id("req_");
        frame["id"] = json!(request_id);
        let (tx, rx) = oneshot::channel();
        self.pending.lock().unwrap().insert(request_id.clone(), tx);
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
        self.pending.lock().unwrap().remove(&request_id);
        result
    }
    pub async fn stop(&self) {
        let _ = self.stop.send(true);
        let mut exited = self.exited.clone();
        while !*exited.borrow() {
            if exited.changed().await.is_err() {
                break;
            }
        }
    }
    pub fn alive(&self) -> bool {
        !*self.exited.borrow()
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
        .unwrap_or_default()
}
