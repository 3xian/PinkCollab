#![cfg(feature = "test-fixtures")]
mod support;
use serde_json::{Value, json};
use support::{Harness, wait_operation};

#[tokio::test]
async fn live_todo_snapshot_survives_lazy_tool_details() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let sessions = format!("{}/api/v4/sessions", h.url);
    let record: Value = client
        .post(&sessions)
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create","hostId":h.host.id,"cwd":h.cwd("todo-snapshot")}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let session = format!("{sessions}/{}", record["id"].as_str().unwrap());
    let response = client
        .post(format!("{session}/commands"))
        .bearer_auth(&credential)
        .json(&json!({"commandId":"prompt","type":"prompt","message":"todo snapshot"}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 202);
    wait_operation(
        &client,
        &format!("{session}/operations/prompt"),
        &credential,
        "succeeded",
    )
    .await;
    let view: Value = client
        .get(&session)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let trace = &view["timeline"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["tool"]["name"] == "todo")
        .expect("live Todo result")["tool"];
    assert!(trace["result"].is_null());
    assert!(trace["detailsAvailable"].as_bool().unwrap());
    assert_eq!(
        trace["todoPhases"][0]["tasks"][0]["content"],
        "Verify (dropped)"
    );
    assert_eq!(trace["todoPhases"][0]["tasks"][0]["status"], "pending");
    assert!(trace["arguments"].is_null());
    let details: Value = client
        .get(format!(
            "{session}/tools/{}",
            trace["callId"].as_str().unwrap()
        ))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(
        details["text"]
            .as_str()
            .unwrap()
            .contains(&"x".repeat(10 * 1024))
    );
}

#[tokio::test]
async fn long_reply_is_complete_and_has_the_same_identity_in_history() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let sessions = format!("{}/api/v4/sessions", h.url);
    let record: Value = client
        .post(&sessions)
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create","hostId":h.host.id,"cwd":h.cwd("long-reply")}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let session = format!("{sessions}/{}", record["id"].as_str().unwrap());
    client
        .post(format!("{session}/commands"))
        .bearer_auth(&credential)
        .json(&json!({"commandId":"prompt","type":"prompt","message":"long reply"}))
        .send()
        .await
        .unwrap();
    wait_operation(
        &client,
        &format!("{session}/operations/prompt"),
        &credential,
        "succeeded",
    )
    .await;
    let view: Value = client
        .get(&session)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let saved: Value = client
        .get(format!("{session}/history"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let live = view["timeline"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["kind"] == "assistant")
        .unwrap();
    let history = saved["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["kind"] == "assistant")
        .unwrap();
    assert_eq!(live["text"], "完整回复。".repeat(20_000));
    assert_eq!(history["text"], live["text"]);
    assert_ne!(history["id"], live["id"]);
    assert_eq!(history["sourceId"], live["sourceId"]);
    assert_eq!(history["messageKey"], live["messageKey"]);
    assert!(history["sourceId"].is_string());
    let trace = view["timeline"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["kind"] == "tool")
        .unwrap();
    let call_id = trace["tool"]["callId"].as_str().unwrap();
    let before: Value = client
        .get(format!("{session}/tools/{call_id}"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(
        client
            .get(format!("{session}/tools/{call_id}"))
            .send()
            .await
            .unwrap()
            .status(),
        401
    );
    let generation = view["runtime"]["generation"].as_str().unwrap();
    client
        .post(format!("{session}/commands"))
        .bearer_auth(&credential)
        .json(&json!({"commandId":"stop","type":"stop_runtime","generation":generation}))
        .send()
        .await
        .unwrap();
    wait_operation(
        &client,
        &format!("{session}/operations/stop"),
        &credential,
        "succeeded",
    )
    .await;
    let after: Value = client
        .get(format!("{session}/tools/{call_id}"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(after, before);
    assert_eq!(
        client
            .get(format!("{session}/tools/absent-call"))
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .status(),
        404
    );
}
