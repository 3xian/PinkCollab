//! Transport-level regression tests for the OMP runtime. They need the fixture binary, which
//! speaks the same NDJSON protocol and can be told to misbehave.
#![cfg(feature = "test-fixtures")]
use pinkcollab_gateway::omp::{Output, Runtime};
use serde_json::json;
use std::time::Duration;

fn fixture(args: &[&str]) -> (tempfile::TempDir, std::sync::Arc<Runtime>, tokio::sync::mpsc::Receiver<Output>) {
    let dir = tempfile::tempdir().unwrap();
    let args = args.iter().map(|arg| (*arg).to_string()).collect::<Vec<_>>();
    let (runtime, output) = Runtime::spawn(env!("CARGO_BIN_EXE_omp-fixture"), &args, dir.path()).unwrap();
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

/// A frame that never ends must fail the transport instead of being buffered without a bound.
#[tokio::test]
async fn unterminated_stdout_fails_the_transport_instead_of_buffering() {
    let (_dir, runtime, mut output) = fixture(&["--flood-stdout"]);
    let reason = tokio::time::timeout(Duration::from_secs(8), next_exit(&mut output))
        .await
        .expect("the reader must reject the frame while the flood is still running");
    assert!(
        reason.as_deref().is_some_and(|reason| reason.contains("NDJSON")),
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
