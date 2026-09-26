//! Transport-level regression tests for the OMP runtime. They need the fixture binary, which
//! speaks the same NDJSON protocol and can be told to misbehave.
#![cfg(feature = "test-fixtures")]
use pinkcollab_gateway::omp::{Output, Runtime};
use serde_json::json;
use std::time::Duration;

fn fixture(
    args: &[&str],
) -> (
    tempfile::TempDir,
    std::sync::Arc<Runtime>,
    tokio::sync::mpsc::Receiver<Output>,
) {
    let dir = tempfile::tempdir().unwrap();
    let args = args
        .iter()
        .map(|arg| (*arg).to_string())
        .collect::<Vec<_>>();
    let (runtime, output) =
        Runtime::spawn(env!("CARGO_BIN_EXE_omp-fixture"), &args, dir.path()).unwrap();
    (dir, runtime, output)
}

async fn next_exit(output: &mut tokio::sync::mpsc::Receiver<Output>) -> Option<String> {
    while let Some(event) = output.recv().await {
        if let Output::Exited(reason) = event {
            return reason;
        }
    }
    panic!("the transport closed without reporting an exit");
}

/// A stop must not wait for an in-flight stdin write: the pipe buffer is far smaller than a
/// permitted prompt, and the process that would drain it is the one being stopped.
#[tokio::test]
async fn stop_completes_while_a_stdin_write_is_blocked() {
    let (_dir, runtime, mut output) = fixture(&["--stall-stdin"]);
    runtime.wait_ready().await.unwrap();
    let writer = runtime.clone();
    let blocked = tokio::spawn(async move {
        writer
            .write(json!({"type":"prompt","message":"x".repeat(512 * 1024)}))
            .await
            .is_err()
    });
    tokio::time::sleep(Duration::from_millis(200)).await;

    tokio::time::timeout(Duration::from_secs(20), runtime.stop())
        .await
        .expect("stop must not depend on the blocked writer");
    assert!(
        blocked.await.unwrap(),
        "the abandoned write must fail once OMP has been killed"
    );
    assert!(!runtime.alive());
    assert!(
        tokio::time::timeout(Duration::from_secs(5), next_exit(&mut output))
            .await
            .is_ok()
    );
}

#[tokio::test]
async fn write_timeout_terminates_runtime_without_an_explicit_stop() {
    let (_dir, runtime, mut output) = fixture(&["--stall-stdin"]);
    runtime.wait_ready().await.unwrap();
    let failed = tokio::time::timeout(
        Duration::from_secs(20),
        runtime.write(json!({"type":"prompt","message":"x".repeat(512 * 1024)})),
    )
    .await
    .expect("the blocked write must time out");
    assert!(failed.is_err());
    let reason = tokio::time::timeout(Duration::from_secs(10), next_exit(&mut output))
        .await
        .expect("a write timeout must clean up OMP without Stop");
    assert!(
        reason
            .as_deref()
            .is_some_and(|reason| reason.contains("write timed out")),
        "unexpected exit reason: {reason:?}"
    );
    assert!(!runtime.alive());
}

/// A frame that never ends must fail the transport instead of being buffered without a bound.
#[tokio::test]
async fn unterminated_stdout_fails_the_transport_instead_of_buffering() {
    let (_dir, runtime, mut output) = fixture(&["--flood-stdout"]);
    let reason = tokio::time::timeout(Duration::from_secs(8), next_exit(&mut output))
        .await
        .expect("the reader must reject the frame while the flood is still running");
    assert!(
        reason
            .as_deref()
            .is_some_and(|reason| reason.contains("NDJSON")),
        "unexpected reason: {reason:?}"
    );
    assert!(!runtime.alive());
}

/// Stopping OMP is not a crash: killing a process we asked to stop must not be reported as an
/// unexpected exit, because the reason travels all the way to what the phone displays.
#[tokio::test]
async fn a_requested_stop_is_not_reported_as_a_crash() {
    // The fixture ignores stdin EOF here, so the stop has to kill it after the grace period.
    let (_dir, runtime, mut output) = fixture(&["--stall-stdin"]);
    runtime.wait_ready().await.unwrap();
    runtime.stop().await;
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(5), next_exit(&mut output))
            .await
            .expect("the reader must report the exit"),
        None
    );
}

#[tokio::test]
async fn concurrent_stops_confirm_one_exit() {
    let (_dir, runtime, mut output) = fixture(&["--stall-stdin"]);
    runtime.wait_ready().await.unwrap();
    let stops = (0..8)
        .map(|_| {
            let runtime = runtime.clone();
            tokio::spawn(async move { runtime.stop_confirmed().await })
        })
        .collect::<Vec<_>>();
    for stop in stops {
        tokio::time::timeout(Duration::from_secs(20), stop)
            .await
            .unwrap()
            .unwrap()
            .unwrap();
    }
    assert!(!runtime.alive());
    assert_eq!(next_exit(&mut output).await, None);
    assert!(output.recv().await.is_none(), "exit must be reported once");
}

#[cfg(windows)]
#[tokio::test]
async fn stop_reaps_the_omp_process_tree() {
    use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
    use windows_sys::Win32::Foundation::WAIT_OBJECT_0;
    use windows_sys::Win32::System::Threading::{OpenProcess, WaitForSingleObject};

    let (dir, runtime, mut output) = fixture(&["--spawn-child-immediate", "--stall-stdin"]);
    runtime.wait_ready().await.unwrap();
    let pid: u32 = std::fs::read_to_string(dir.path().join("child.pid"))
        .unwrap()
        .parse()
        .unwrap();
    let raw = unsafe { OpenProcess(0x0010_0000, 0, pid) }; // SYNCHRONIZE
    assert!(!raw.is_null(), "fixture child must be running");
    let child = unsafe { OwnedHandle::from_raw_handle(raw) };
    runtime.stop_confirmed().await.unwrap();
    // Windows can report an empty job just before the descendant's process handle becomes
    // signaled. Keep checking the actual child, but allow that brief completion window.
    assert_eq!(
        unsafe { WaitForSingleObject(child.as_raw_handle(), 5000) },
        WAIT_OBJECT_0,
        "Stop left fixture child {pid} running"
    );
    assert_eq!(next_exit(&mut output).await, None);
}

#[cfg(unix)]
#[tokio::test]
async fn stop_reaps_the_unix_omp_process_group() {
    let (dir, runtime, mut output) = fixture(&["--spawn-child-immediate", "--stall-stdin"]);
    runtime.wait_ready().await.unwrap();
    let pid: i32 = std::fs::read_to_string(dir.path().join("child.pid"))
        .unwrap()
        .parse()
        .unwrap();
    assert!(unix_child_running(pid), "fixture child must be running");
    runtime.stop_confirmed().await.unwrap();
    assert!(
        !unix_child_running(pid),
        "Stop left a child process running"
    );
    assert_eq!(next_exit(&mut output).await, None);
}

#[cfg(unix)]
fn unix_child_running(pid: i32) -> bool {
    if unsafe { libc::kill(pid, 0) } != 0 {
        return false;
    }
    #[cfg(target_os = "linux")]
    {
        let Ok(stat) = std::fs::read_to_string(format!("/proc/{pid}/stat")) else {
            return false;
        };
        stat.rsplit_once(") ")
            .and_then(|(_, fields)| fields.split_whitespace().next())
            .is_some_and(|state| state != "Z" && state != "X")
    }
    #[cfg(target_os = "macos")]
    {
        let mut info = std::mem::MaybeUninit::<libc::proc_bsdshortinfo>::uninit();
        let size = std::mem::size_of::<libc::proc_bsdshortinfo>() as i32;
        let read = unsafe {
            *libc::__error() = 0;
            libc::proc_pidinfo(
                pid,
                libc::PROC_PIDT_SHORTBSDINFO,
                0,
                info.as_mut_ptr().cast(),
                size,
            )
        };
        if read == 0 {
            let err = std::io::Error::last_os_error();
            if matches!(err.raw_os_error(), Some(0) | Some(libc::ESRCH)) {
                return false;
            }
            panic!("cannot inspect fixture child {pid}: {err}");
        }
        assert_eq!(read, size, "cannot inspect fixture child {pid}");
        unsafe { info.assume_init() }.pbsi_status != libc::SZOMB
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos")))]
    {
        true
    }
}
