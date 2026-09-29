//! Bounded probes for external administrative tools.
use anyhow::{Context, Result, ensure};
use std::{
    io::Read,
    process::{Command, Output, Stdio},
    time::{Duration, Instant},
};
pub fn output(command: &mut Command, timeout: Duration) -> Result<Output> {
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        command.process_group(0);
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(windows_sys::Win32::System::Threading::CREATE_SUSPENDED);
    }
    let deadline = Instant::now() + timeout;
    let mut child = command
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .context("cannot start administrative command")?;
    #[cfg(windows)]
    let job = match crate::windows_job::Job::attach_and_resume(&child) {
        Ok(job) => job,
        Err(error) => {
            let _ = child.kill();
            let _ = child.wait();
            return Err(error);
        }
    };
    let (send, receive) = std::sync::mpsc::channel();
    fn collect(
        mut pipe: impl Read + Send + 'static,
        stream: usize,
        send: std::sync::mpsc::Sender<(usize, std::io::Result<Vec<u8>>)>,
    ) {
        std::thread::spawn(move || {
            let mut bytes = Vec::new();
            let result = pipe.read_to_end(&mut bytes).map(|_| bytes);
            let _ = send.send((stream, result));
        });
    }
    collect(child.stdout.take().unwrap(), 0, send.clone());
    collect(child.stderr.take().unwrap(), 1, send);
    let result = (|| {
        let mut streams = [None, None];
        let mut status = None;
        loop {
            while let Ok((stream, bytes)) = receive.try_recv() {
                streams[stream] = Some(bytes.context("cannot read command output")?);
            }
            if status.is_none() {
                status = child.try_wait()?;
            }
            if let Some(status) = status
                && streams.iter().all(Option::is_some)
            {
                return Ok(Output {
                    status,
                    stdout: streams[0].take().unwrap(),
                    stderr: streams[1].take().unwrap(),
                });
            }
            ensure!(
                Instant::now() < deadline,
                "administrative command timed out after {} ms",
                timeout.as_millis()
            );
            std::thread::sleep(Duration::from_millis(5));
        }
    })();
    // Terminate descendants too: they may retain inherited output handles after the
    // direct child exits. Reader completion is never awaited beyond the deadline.
    #[cfg(unix)]
    unsafe {
        libc::kill(-(child.id() as i32), libc::SIGKILL);
    }
    #[cfg(windows)]
    drop(job);
    let _ = child.kill();
    let _ = child.wait();
    result
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    #[test]
    fn administrative_probe_timeout_kills_and_reaps_child() {
        let start = Instant::now();
        let error = output(
            Command::new("/bin/sleep").arg("3"),
            Duration::from_millis(30),
        )
        .unwrap_err();
        assert!(error.to_string().contains("timed out"));
        assert!(start.elapsed() < Duration::from_secs(2));
    }
    #[test]
    fn timeout_includes_inherited_output_handles() {
        let start = Instant::now();
        let error = output(
            Command::new("/bin/sh").args(["-c", "/bin/sleep 3 &"]),
            Duration::from_millis(50),
        )
        .unwrap_err();
        assert!(error.to_string().contains("timed out"));
        assert!(start.elapsed() < Duration::from_millis(500));
    }
}
