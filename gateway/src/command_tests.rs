use super::*;
use std::io::{BufRead, BufReader, Write};

fn fixture(mode: &str, root: &std::path::Path) -> Command {
    let mut command = Command::new(std::env::current_exe().unwrap());
    command
        .args(["--exact", "command::execution_tests::child", "--nocapture"])
        .env("PINKCOLLAB_COMMAND_TEST_MODE", mode)
        .env("PINKCOLLAB_COMMAND_TEST_ROOT", root);
    command
}
#[test]
fn child() {
    let Ok(mode) = std::env::var("PINKCOLLAB_COMMAND_TEST_MODE") else {
        return;
    };
    let root = std::path::PathBuf::from(std::env::var_os("PINKCOLLAB_COMMAND_TEST_ROOT").unwrap());
    match mode.as_str() {
        "stream" => {
            let output =
                interactive_output(&mut fixture("reader", &root), Duration::from_secs(5)).unwrap();
            assert!(output.status.success());
            assert!(output.stdout.is_empty());
            assert!(output.stderr.is_empty());
        }
        "blocked-output" => {
            let error =
                interactive_output(&mut fixture("flood", &root), Duration::from_millis(500))
                    .unwrap_err();
            assert!(error.to_string().contains("timed out"));
            // Acquiring both locks proves no detached forwarding thread retained them.
            drop(std::io::stdout().lock());
            drop(std::io::stderr().lock());
            std::fs::write(root.join("bounded"), "done").unwrap();
            std::process::exit(0);
        }
        "flood" => {
            let bytes = [b'x'; 8192];
            loop {
                std::io::stdout().write_all(&bytes).unwrap();
            }
        }
        #[cfg(unix)]
        "pty" => {
            let error = interactive_output(
                Command::new("/bin/sh").args(["-c", "echo terminal-ready; read answer"]),
                Duration::from_secs(5),
            )
            .unwrap_err();
            assert!(error.is::<Interrupted>());
            assert_eq!(unsafe { libc::tcgetpgrp(0) }, unsafe { libc::getpgrp() });
            std::fs::write(root.join("cancelled"), "done").unwrap();
        }
        "reader" => {
            println!("approval-url");
            std::io::stdout().flush().unwrap();
            eprintln!("approval-stderr");
            let mut line = String::new();
            std::io::stdin().read_line(&mut line).unwrap();
            assert_eq!(line.trim(), "approved");
            let deadline = Instant::now() + Duration::from_secs(4);
            while !root.join("release").exists() {
                assert!(Instant::now() < deadline, "live output was not delivered");
                std::thread::sleep(Duration::from_millis(5));
            }
        }
        "descendants" => {
            let mut child = fixture("late-write", &root).spawn().unwrap();
            std::fs::write(root.join("spawned"), "ready").unwrap();
            child.wait().unwrap();
        }
        "late-write" => {
            std::thread::sleep(Duration::from_secs(2));
            std::fs::write(root.join("leaked"), "leaked").unwrap();
        }
        "null-input" => {
            let mut line = String::new();
            assert_eq!(std::io::stdin().read_line(&mut line).unwrap(), 0);
        }
        _ => panic!("unknown fixture"),
    }
}
#[test]
fn interactive_streams_before_exit_and_inherits_input() {
    let root = tempfile::tempdir().unwrap();
    let mut child = fixture("stream", root.path())
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    child
        .stdin
        .take()
        .unwrap()
        .write_all(b"approved\n")
        .unwrap();
    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    let (send, receive) = std::sync::mpsc::channel();
    for pipe in [Box::new(stdout) as Box<dyn Read + Send>, Box::new(stderr)] {
        let send = send.clone();
        std::thread::spawn(move || {
            for line in BufReader::new(pipe).lines() {
                let line = line.unwrap();
                if line == "approval-url" || line == "approval-stderr" {
                    let _ = send.send(line);
                }
            }
        });
    }
    let first = receive.recv_timeout(Duration::from_secs(3));
    let second = receive.recv_timeout(Duration::from_secs(3));
    let running = child.try_wait().unwrap().is_none();
    std::fs::write(root.path().join("release"), "go").unwrap();
    let status = child.wait().unwrap();
    assert!(first.is_ok() && second.is_ok(), "both streams must be live");
    assert_ne!(first.unwrap(), second.unwrap());
    assert!(running);
    assert!(status.success());
}
#[test]
fn captured_input_is_closed_and_both_modes_kill_descendants() {
    let root = tempfile::tempdir().unwrap();
    assert!(
        output(
            &mut fixture("null-input", root.path()),
            Duration::from_secs(3)
        )
        .unwrap()
        .status
        .success()
    );
    for run in [output, interactive_output] {
        let start = Instant::now();
        let error = run(
            &mut fixture("descendants", root.path()),
            Duration::from_millis(800),
        )
        .unwrap_err();
        assert!(error.to_string().contains("timed out"));
        assert!(start.elapsed() < Duration::from_secs(2));
        assert!(root.path().join("spawned").exists());
        std::thread::sleep(Duration::from_secs(2));
        assert!(!root.path().join("leaked").exists());
        std::fs::remove_file(root.path().join("spawned")).unwrap();
    }
}

#[test]
fn blocked_display_does_not_outlive_command_timeout() {
    let root = tempfile::tempdir().unwrap();
    let mut child = fixture("blocked-output", root.path())
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(4);
    while !root.path().join("bounded").exists() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(10));
    }
    let completed = root.path().join("bounded").exists();
    let _ = child.kill();
    let _ = child.wait();
    assert!(
        completed,
        "output forwarding must not hold parent output locks after timeout"
    );
}
#[cfg(unix)]
#[test]
fn terminal_ctrl_c_is_reported_and_foreground_is_restored() {
    use std::os::{fd::FromRawFd, unix::process::CommandExt};
    let root = tempfile::tempdir().unwrap();
    let (mut master, mut slave) = (-1, -1);
    assert_eq!(
        unsafe {
            libc::openpty(
                &mut master,
                &mut slave,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            )
        },
        0
    );
    let mut master = unsafe { std::fs::File::from_raw_fd(master) };
    let slave = unsafe { std::fs::File::from_raw_fd(slave) };
    let mut command = fixture("pty", root.path());
    command
        .stdin(slave.try_clone().unwrap())
        .stdout(slave.try_clone().unwrap())
        .stderr(slave);
    unsafe {
        command.pre_exec(|| {
            if libc::setsid() == -1 || libc::ioctl(0, libc::TIOCSCTTY as _, 0) == -1 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
    let mut child = command.spawn().unwrap();
    drop(command);
    let reader = master.try_clone().unwrap();
    let (send, receive) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        for line in BufReader::new(reader).lines() {
            let Ok(line) = line else {
                break;
            };
            if line.contains("terminal-ready") {
                // Keep draining: macOS can wait for terminal output during child exit.
                let _ = send.send(());
            }
        }
    });
    let ready = receive.recv_timeout(Duration::from_secs(3)).is_ok();
    if ready {
        master.write_all(&[3]).unwrap();
    }
    let deadline = Instant::now() + Duration::from_secs(3);
    while !root.path().join("cancelled").exists() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(10));
    }
    let cancelled = root.path().join("cancelled").exists();
    let _ = child.kill();
    let _ = child.wait();
    assert!(
        ready && cancelled,
        "terminal Ctrl+C must return cancellation and restore foreground ownership"
    );
}
