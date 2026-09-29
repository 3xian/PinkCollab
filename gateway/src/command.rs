//! Bounded probes for external administrative tools.
use anyhow::{Context, Result, ensure};
use std::{
    io::Read,
    process::{Command, Output, Stdio},
    time::{Duration, Instant},
};
pub fn output(command: &mut Command, timeout: Duration) -> Result<Output> {
    run(command, timeout, false)
}
/// Inherit all terminal streams. Output bytes are empty in this mode;
/// the child displays diagnostics directly without parent forwarding threads.
pub fn interactive_output(command: &mut Command, timeout: Duration) -> Result<Output> {
    run(command, timeout, true)
}
fn run(command: &mut Command, timeout: Duration, interactive: bool) -> Result<Output> {
    #[cfg(unix)]
    let terminal = terminal::Foreground::prepare(command, interactive)?;
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
        .stdin(if interactive {
            Stdio::inherit()
        } else {
            Stdio::null()
        })
        .stdout(if interactive {
            Stdio::inherit()
        } else {
            Stdio::piped()
        })
        .stderr(if interactive {
            Stdio::inherit()
        } else {
            Stdio::piped()
        })
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
    if !interactive {
        collect(child.stdout.take().unwrap(), 0, send.clone());
        collect(child.stderr.take().unwrap(), 1, send);
    }
    let result = (|| {
        let mut streams = if interactive {
            [Some(Vec::new()), Some(Vec::new())]
        } else {
            [None, None]
        };
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
    #[cfg(unix)]
    drop(terminal);
    let output = result?;
    if interrupted(output.status) {
        return Err(Interrupted.into());
    }
    Ok(output)
}
#[derive(Debug)]
pub struct Interrupted;
impl std::fmt::Display for Interrupted {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Administrative command interrupted.")
    }
}
impl std::error::Error for Interrupted {}
fn interrupted(status: std::process::ExitStatus) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::process::ExitStatusExt;
        if status.signal() == Some(libc::SIGINT) {
            return true;
        }
    }
    // Shell convention and Windows STATUS_CONTROL_C_EXIT.
    matches!(status.code(), Some(130) | Some(-1073741510))
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

// Give the isolated process group terminal input without SIGTTIN, and restore
// the parent's foreground group on every exit path (including spawn failures).
#[cfg(unix)]
mod terminal {
    use super::*;
    use std::os::unix::process::CommandExt;
    pub(super) struct Foreground(Option<libc::pid_t>);
    unsafe fn foreground(group: libc::pid_t) -> std::io::Result<()> {
        unsafe {
            let mut mask = std::mem::zeroed();
            let mut old = std::mem::zeroed();
            libc::sigemptyset(&mut mask);
            libc::sigaddset(&mut mask, libc::SIGTTOU);
            let error = libc::pthread_sigmask(libc::SIG_BLOCK, &mask, &mut old);
            if error != 0 {
                return Err(std::io::Error::from_raw_os_error(error));
            }
            let result = libc::tcsetpgrp(libc::STDIN_FILENO, group);
            let error = std::io::Error::last_os_error();
            libc::pthread_sigmask(libc::SIG_SETMASK, &old, std::ptr::null_mut());
            if result == -1 { Err(error) } else { Ok(()) }
        }
    }
    impl Foreground {
        pub(super) fn prepare(command: &mut Command, interactive: bool) -> Result<Self> {
            let group = unsafe { libc::tcgetpgrp(libc::STDIN_FILENO) };
            if !interactive || group == -1 {
                return Ok(Self(None));
            }
            ensure!(
                group == unsafe { libc::getpgrp() },
                "interactive command requires a foreground terminal"
            );
            unsafe {
                command.pre_exec(|| foreground(libc::getpid()));
            }
            Ok(Self(Some(group)))
        }
    }
    impl Drop for Foreground {
        fn drop(&mut self) {
            if let Some(group) = self.0 {
                let _ = unsafe { foreground(group) };
            }
        }
    }
}

#[cfg(test)]
#[path = "command_tests.rs"]
mod execution_tests;
