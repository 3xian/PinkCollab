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
    runtime
        .request(serde_json::json!({"type":"abort"}))
        .await
        .unwrap();
    runtime.stop().await;
    drain.await.unwrap();
}
