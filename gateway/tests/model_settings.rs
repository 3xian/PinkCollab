#![cfg(feature = "test-fixtures")]
mod support;

use serde_json::{Value, json};
use support::{Harness, command_succeeds, create_session, runtime_generation};

#[tokio::test]
async fn resumed_conversation_uses_current_omp_default_instead_of_historical_model() {
    let h = Harness::new(
        1,
        vec![
            "--restore-smart-model".into(),
            "--record-prompt-frame".into(),
        ],
    )
    .await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let cwd = h.cwd("resume-default-model");
    let session = create_session(&client, &h, &credential, &cwd).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "start",
        json!({"type":"start_runtime"}),
    )
    .await;
    let generation = runtime_generation(&client, &session, &credential).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "stop",
        json!({"type":"stop_runtime","generation":generation}),
    )
    .await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "resume",
        json!({"type":"prompt","message":"continue"}),
    )
    .await;

    let snapshot: Value = client
        .get(&session)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(snapshot["runtime"]["model"]["id"], "fast");
    let prompt: Value = serde_json::from_str(
        &std::fs::read_to_string(std::path::Path::new(&cwd).join("last-prompt.json")).unwrap(),
    )
    .unwrap();
    assert_eq!(prompt["fixtureModelId"], "fast");
}

#[tokio::test]
async fn fast_mode_commands_update_runtime_snapshot_in_both_directions() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let session = create_session(&client, &h, &credential, &h.cwd("fast-mode")).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "start",
        json!({"type":"start_runtime"}),
    )
    .await;
    let generation = runtime_generation(&client, &session, &credential).await;
    for (command_id, enabled) in [("fast-on", true), ("fast-off", false)] {
        command_succeeds(
            &client,
            &session,
            &credential,
            command_id,
            json!({"type":"set_fast_mode","generation":generation,"enabled":enabled}),
        )
        .await;
        let snapshot: Value = client
            .get(&session)
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        assert_eq!(snapshot["runtime"]["model"]["fastModeEnabled"], enabled);
        assert_eq!(snapshot["runtime"]["model"]["fastModeActive"], enabled);
    }
}
