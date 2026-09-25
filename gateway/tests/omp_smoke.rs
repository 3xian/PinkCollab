#[tokio::test]
#[ignore = "requires an installed OMP runtime; does not send a model prompt"]
async fn real_omp_rpc_ui_handshake() {
    let dir = tempfile::tempdir().unwrap();
    let executable = std::env::var("OMP_EXECUTABLE").unwrap_or_else(|_| "omp".into());
    let (runtime, mut output) =
        pinkcollab_gateway::omp::Runtime::spawn(&executable, &[], dir.path()).unwrap();
    let drain = tokio::spawn(async move { while output.recv().await.is_some() {} });
    runtime.wait_ready().await.unwrap();
    let state = runtime
        .request(serde_json::json!({"type":"get_state"}))
        .await
        .unwrap();
    assert!(state["data"].is_object());
    assert!(state["data"]["isSettled"].is_boolean());
    let session_file = state["data"]["sessionFile"]
        .as_str()
        .expect("OMP session reference")
        .to_owned();
    let models = runtime
        .request(serde_json::json!({"type":"get_available_models"}))
        .await
        .unwrap();
    assert!(models["data"]["models"].is_array());
    let thinking = runtime
        .request(serde_json::json!({"type":"get_available_thinking_levels"}))
        .await
        .unwrap();
    assert!(thinking["data"]["levels"].is_array());
    let page = runtime
        .request(serde_json::json!({"type":"get_messages_page","limit":10}))
        .await
        .unwrap();
    assert!(page["data"].is_object());
    runtime
        .request(serde_json::json!({"type":"abort"}))
        .await
        .unwrap();
    runtime.stop().await;
    drain.await.unwrap();
    let (resumed, mut output) =
        pinkcollab_gateway::omp::Runtime::spawn(&executable, &[], dir.path()).unwrap();
    let drain = tokio::spawn(async move { while output.recv().await.is_some() {} });
    resumed.wait_ready().await.unwrap();
    let loaded = resumed
        .request(serde_json::json!({"type":"switch_session","sessionPath":session_file}))
        .await
        .unwrap();
    assert_ne!(loaded["data"]["cancelled"], true);
    let state = resumed
        .request(serde_json::json!({"type":"get_state"}))
        .await
        .unwrap();
    assert_eq!(state["data"]["sessionFile"], session_file);
    resumed.stop().await;
    drain.await.unwrap();
}
