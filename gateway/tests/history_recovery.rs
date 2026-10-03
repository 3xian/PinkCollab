#![cfg(feature = "test-fixtures")]
mod support;
use serde_json::{Value, json};
use support::{Harness, wait_operation};

#[tokio::test]
async fn fresh_session_without_transcript_has_empty_history_before_and_after_stop() {
    let h = Harness::new(1, vec!["--lazy-history".into()]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let sessions = format!("{}/api/v4/sessions", h.url);
    let cwd = h.cwd("empty-history");
    let record: Value = client
        .post(&sessions)
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create","hostId":h.host.id,"cwd":cwd}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let session = format!("{sessions}/{}", record["id"].as_str().unwrap());
    let commands = format!("{session}/commands");
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"start","type":"start_runtime"}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let _receipt = wait_operation(
        &client,
        &format!("{session}/operations/start"),
        &credential,
        "succeeded",
    )
    .await;

    for _ in 0..2 {
        let response = client
            .get(format!("{session}/history"))
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 200);
        let page: Value = response.json().await.unwrap();
        assert_eq!(page["items"], json!([]));
        assert!(page["source"].is_null());
    }
    assert_eq!(client.post(&commands).bearer_auth(&credential)
        .json(&json!({"commandId":"stop","type":"stop_runtime","generation":support::runtime_generation(&client, &session, &credential).await}))
        .send().await.unwrap().status(), 202);
    wait_operation(
        &client,
        &format!("{session}/operations/stop"),
        &credential,
        "succeeded",
    )
    .await;
    let response = client
        .get(format!("{session}/history"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    assert_eq!(response.json::<Value>().await.unwrap()["items"], json!([]));
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"resume-empty","type":"start_runtime"}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let _receipt = wait_operation(
        &client,
        &format!("{session}/operations/resume-empty"),
        &credential,
        "succeeded",
    )
    .await;
    let response = client
        .get(format!("{session}/history"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    assert_eq!(response.json::<Value>().await.unwrap()["items"], json!([]));
    assert_eq!(client.post(&commands).bearer_auth(&credential)
        .json(&json!({"commandId":"first","type":"prompt","message":"hello","generation":support::runtime_generation(&client, &session, &credential).await}))
        .send().await.unwrap().status(), 202);
    wait_operation(
        &client,
        &format!("{session}/operations/first"),
        &credential,
        "succeeded",
    )
    .await;
    let response = client
        .get(format!("{session}/history"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    assert!(
        !response.json::<Value>().await.unwrap()["items"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    let transcript = std::fs::read_dir(&cwd)
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .find(|path| path.extension().is_some_and(|ext| ext == "jsonl"))
        .unwrap();
    std::fs::remove_file(transcript).unwrap();
    let response = client
        .get(format!("{session}/history"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 503);
    assert_eq!(
        response.json::<Value>().await.unwrap()["code"],
        "history_unavailable"
    );
}

#[tokio::test]
async fn aborted_prompt_with_missing_transcript_does_not_lose_history_mapping() {
    let h = Harness::new(1, vec!["--lazy-history".into()]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let cwd = h.cwd("aborted-history");
    let sessions = format!("{}/api/v4/sessions", h.url);
    let record: Value = client
        .post(&sessions)
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create","hostId":h.host.id,"cwd":cwd}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let id = record["id"].as_str().unwrap();
    let session = format!("{sessions}/{id}");
    let commands = format!("{session}/commands");
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"start","type":"start_runtime"}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let _receipt = wait_operation(
        &client,
        &format!("{session}/operations/start"),
        &credential,
        "succeeded",
    )
    .await;
    let generation = support::runtime_generation(&client, &session, &credential).await;
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"hold","type":"prompt","message":"hold","generation":generation}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    wait_operation(
        &client,
        &format!("{session}/operations/hold"),
        &credential,
        "pending",
    )
    .await;
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"abort","type":"interrupt","generation":generation}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    wait_operation(
        &client,
        &format!("{session}/operations/abort"),
        &credential,
        "succeeded",
    )
    .await;
    wait_operation(
        &client,
        &format!("{session}/operations/hold"),
        &credential,
        "cancelled",
    )
    .await;
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"stop","type":"stop_runtime","generation":generation}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    wait_operation(
        &client,
        &format!("{session}/operations/stop"),
        &credential,
        "succeeded",
    )
    .await;
    let original = h
        .store
        .v2_session(id)
        .unwrap()
        .unwrap()
        .engine_session_ref
        .unwrap();
    assert!(std::path::Path::new(&original).is_file());
    std::fs::remove_file(&original).unwrap();
    let response = client
        .get(format!("{session}/history"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 503);
    assert_eq!(
        response.json::<Value>().await.unwrap()["code"],
        "history_unavailable"
    );
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"resume","type":"start_runtime"}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let failed = wait_operation(
        &client,
        &format!("{session}/operations/resume"),
        &credential,
        "failed",
    )
    .await;
    assert_eq!(failed["error"]["code"], "history_unavailable");
    assert_eq!(
        h.store
            .v2_session(id)
            .unwrap()
            .unwrap()
            .engine_session_ref
            .as_deref(),
        Some(original.as_str())
    );
}
