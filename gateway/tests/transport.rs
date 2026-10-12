//! Transport-level regression tests for the OMP runtime. They need the fixture binary, which
//! speaks the same NDJSON protocol and can be told to misbehave.
#![cfg(feature = "test-fixtures")]
use pinkcollab_gateway::omp::{Output, Runtime, TransportError, TransportErrorKind};
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
    let failure = failed.expect_err("the blocked write must fail");
    let reason = tokio::time::timeout(Duration::from_secs(10), next_exit(&mut output))
        .await
        .expect("a write timeout must clean up OMP without Stop");
    assert!(
        reason
            .as_deref()
            .is_some_and(|reason| reason.contains(&format!("{failure:#}"))),
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

// Draining concurrently is essential: production deliberately queues only one logical frame.
fn drain(
    mut output: tokio::sync::mpsc::Receiver<Output>,
) -> tokio::task::JoinHandle<(Vec<serde_json::Value>, Option<String>)> {
    tokio::spawn(async move {
        let mut frames = Vec::new();
        while let Some(event) = output.recv().await {
            match event {
                Output::Frame(frame) => {
                    assert_ne!(
                        frame["type"], "rpc_chunk",
                        "wire envelopes must remain private"
                    );
                    frames.push(frame);
                }
                Output::Exited(reason) => return (frames, reason),
            }
        }
        panic!("output closed without an exit");
    })
}

#[tokio::test]
async fn v1_small_reply_remains_compatible() {
    let (_dir, runtime, output) = fixture(&[]);
    let drained = drain(output);
    runtime.wait_ready().await.unwrap();
    let reply = runtime
        .request(json!({"type":"get_available_models"}))
        .await
        .unwrap();
    assert_eq!(reply["data"]["models"].as_array().unwrap().len(), 2);
    assert_eq!(reply["data"]["models"][0]["id"], "fast");
    assert_eq!(
        reply["data"]["models"][1]["thinking"]["efforts"],
        json!(["medium", "high"])
    );
    runtime.stop().await;
    assert_eq!(drained.await.unwrap().1, None);
}

#[tokio::test]
async fn v1_catalog_overflow_preserves_the_upstream_error() {
    let (_dir, runtime, output) = fixture(&["--large-models"]);
    let drained = drain(output);
    let error = runtime
        .request(json!({"type":"get_available_models"}))
        .await
        .unwrap_err();
    assert_eq!(
        error.to_string(),
        "OMP rejected command: RPC response exceeded the transport limit"
    );
    // A rejected logical response is not a broken transport.
    let response = runtime
        .request(json!({"type":"fixture_echo","sequence":7}))
        .await
        .unwrap();
    assert_eq!(response["data"], json!({"sequence":7,"negotiated":false}));
    runtime.stop().await;
    assert_eq!(drained.await.unwrap().1, None);
}

#[tokio::test]
async fn concurrent_business_requests_wait_for_negotiation() {
    let (_dir, runtime, output) = fixture(&["--rpc-v2"]);
    let drained = drain(output);
    let mut calls = Vec::new();
    for sequence in 0..8 {
        let runtime = runtime.clone();
        calls.push(tokio::spawn(async move {
            let id = format!("early-{sequence}");
            let reply = runtime
                .request_with_id(
                    id.clone(),
                    json!({"type":"fixture_echo","sequence":sequence}),
                )
                .await
                .unwrap();
            assert_eq!(reply["id"], id);
            assert_eq!(
                reply["data"],
                json!({"sequence":sequence,"negotiated":true})
            );
        }));
    }
    for call in calls {
        tokio::time::timeout(Duration::from_secs(5), call)
            .await
            .unwrap()
            .unwrap();
    }
    runtime.stop().await;
    assert_eq!(drained.await.unwrap().1, None);
}

#[tokio::test]
async fn v2_negotiates_then_reassembles_large_models_and_utf8_events() {
    let (_dir, runtime, output) = fixture(&["--rpc-v2", "--large-models"]);
    let drained = drain(output);
    runtime.wait_ready().await.unwrap();
    for sequence in 0..3 {
        let reused = runtime
            .request_with_id(
                "protocol-1".to_owned(),
                json!({"type":"fixture_echo","sequence":sequence}),
            )
            .await
            .unwrap();
        assert_eq!(reused["id"], "protocol-1");
        assert_eq!(
            reused["data"],
            json!({"sequence":sequence,"negotiated":true})
        );
        let reply = runtime
            .request_with_id(
                format!("catalog-{sequence}"),
                json!({"type":"get_available_models"}),
            )
            .await
            .unwrap();
        assert_eq!(reply["id"], format!("catalog-{sequence}"));
        assert_eq!(reply["success"], true);
        let models = reply["data"]["models"].as_array().unwrap();
        assert_eq!(models.len(), 642);
        assert!(serde_json::to_vec(&reply).unwrap().len() > 1024 * 1024);
        assert_eq!(models[0]["id"], "fast");
        assert_eq!(models[1]["id"], "smart");
        for (index, model) in models[2..].iter().enumerate() {
            assert_eq!(
                *model,
                json!({
                    "provider":"fixture","id":format!("large-{index}"),"name":format!("Fixture Large {index}"),
                    "reasoning":true,"contextWindow":200000,"maxTokens":8192,
                    "cost":{"input":1.25,"output":5,"cacheRead":0.1,"cacheWrite":1.5},
                    "input":["text","image"],"thinking":{"mode":"effort","efforts":["low","medium","high"]},
                    "fixtureMetadata":{"index":index,"description":"界".repeat(700)}
                })
            );
        }
        let small = runtime
            .request_with_id(
                format!("small-{sequence}"),
                json!({"type":"fixture_echo","sequence":sequence}),
            )
            .await
            .unwrap();
        assert_eq!(small["id"], format!("small-{sequence}"));
        assert_eq!(
            small["data"],
            json!({"sequence":sequence,"negotiated":true})
        );
    }
    runtime
        .request(json!({"type":"fixture_large_event"}))
        .await
        .unwrap();
    runtime.stop().await;
    let (frames, reason) = drained.await.unwrap();
    assert_eq!(reason, None);
    let events = frames
        .iter()
        .filter(|frame| frame["type"] == "fixture_event")
        .collect::<Vec<_>>();
    assert_eq!(events.len(), 1);
    assert_eq!(events[0]["text"], "界🙂".repeat(240_000));
}

#[tokio::test]
async fn malformed_v2_streams_fail_boundedly_and_release_pending_calls() {
    use TransportErrorKind::*;
    for (mode, expected) in [
        ("wrong-index", InvalidChunk),
        ("wrong-id", InvalidChunk),
        ("wrong-count", InvalidChunk),
        ("duplicate", InvalidChunk),
        ("missing", InvalidChunk),
        ("interleaved", InvalidChunk),
        ("short-length", InvalidChunk),
        ("long-length", InvalidChunk),
        ("small-length", InvalidChunk),
        ("corrupt-base64", InvalidChunk),
        ("unpadded-base64", InvalidChunk),
        ("noncanonical-base64", InvalidChunk),
        ("invalid-json", InvalidJson),
        ("invalid-utf8", InvalidUtf8),
        ("non-object", InvalidFrame),
        ("nested-chunk", InvalidFrame),
        ("oversized-reassembly", ReassemblyTooLarge),
        ("advertised-reassembly", ReassemblyTooLarge),
        ("midchunk-exit", IncompleteChunk),
        ("physical-local", PhysicalFrameTooLarge),
        ("physical-advertised", PhysicalFrameTooLarge),
        ("unterminated-local", PhysicalFrameTooLarge),
        ("unterminated-advertised", PhysicalFrameTooLarge),
        ("count-one", InvalidChunk),
        ("count-too-many", InvalidChunk),
        ("empty-id", InvalidChunk),
        ("long-id", InvalidChunk),
        ("oversized-chunk", ChunkPayloadTooLarge),
    ] {
        let (_dir, runtime, output) = fixture(&["--rpc-v2", "--rpc-case", mode]);
        let drained = drain(output);
        runtime.wait_ready().await.unwrap();
        let (first, second) = tokio::time::timeout(Duration::from_secs(8), async {
            tokio::join!(
                runtime.request(json!({"type":"fixture_malformed"})),
                runtime.request(json!({"type":"fixture_echo","sequence":99})),
            )
        })
        .await
        .expect("malformed stream must release all pending requests");
        let error = first.expect_err(mode);
        let transport = error.downcast::<TransportError>().unwrap_or_else(|error| {
            panic!("{mode}: expected typed transport error, got {error:#}")
        });
        assert_eq!(transport.kind, expected, "{mode}: {transport}");
        // The ordinary reply may complete before the malformed request, but never hangs.
        if let Ok(reply) = second {
            assert_eq!(reply["data"]["sequence"], 99);
        }
        let (_, reason) = tokio::time::timeout(Duration::from_secs(8), drained)
            .await
            .expect("malformed stream must terminate")
            .unwrap();
        let diagnostic = reason
            .as_deref()
            .expect("transport failure must have a diagnostic");
        assert!(!diagnostic.is_empty(), "{mode}");
        assert!(!runtime.alive(), "{mode}");
        tokio::time::timeout(Duration::from_secs(5), runtime.stop())
            .await
            .unwrap();
    }
}

#[tokio::test]
async fn negotiation_failures_release_ready_and_stop() {
    for mode in ["reject", "wrong-version", "timeout", "exit"] {
        let (_dir, runtime, output) = fixture(&["--rpc-v2", "--negotiation", mode]);
        let drained = drain(output);
        tokio::time::timeout(Duration::from_secs(13), runtime.wait_ready())
            .await
            .expect("negotiation deadline must be bounded")
            .expect_err(mode);
        let (_, reason) = tokio::time::timeout(Duration::from_secs(5), drained)
            .await
            .unwrap()
            .unwrap();
        assert!(
            reason.as_deref().is_some_and(|reason| !reason.is_empty()),
            "{mode}"
        );
        tokio::time::timeout(Duration::from_secs(5), runtime.stop())
            .await
            .unwrap();
        assert!(!runtime.alive());
    }
}

#[tokio::test]
async fn an_unfinished_chunk_sequence_has_a_deadline() {
    let (_dir, runtime, output) = fixture(&["--rpc-v2", "--rpc-case", "missing-stall"]);
    let drained = drain(output);
    runtime.wait_ready().await.unwrap();
    let error = tokio::time::timeout(
        Duration::from_secs(20),
        runtime.request(json!({"type":"fixture_malformed"})),
    )
    .await
    .unwrap()
    .expect_err("unfinished chunks cannot keep a request pending");
    assert_eq!(
        error.downcast::<TransportError>().unwrap().kind,
        TransportErrorKind::Timeout
    );
    let (_, reason) = tokio::time::timeout(Duration::from_secs(8), drained)
        .await
        .unwrap()
        .unwrap();
    assert!(reason.as_deref().is_some_and(|reason| !reason.is_empty()));
    tokio::time::timeout(Duration::from_secs(5), runtime.stop())
        .await
        .unwrap();
    assert!(!runtime.alive());
}
