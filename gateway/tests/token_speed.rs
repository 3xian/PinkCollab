#![cfg(feature = "test-fixtures")]
mod support;
use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use std::time::Duration;
use support::{Harness, wait_operation};
use tokio_tungstenite::tungstenite::{Message, client::IntoClientRequest};

async fn detail(client: &reqwest::Client, session: &str, credential: &str) -> Value {
    client
        .get(session)
        .bearer_auth(credential)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap()
}

async fn command(client: &reqwest::Client, session: &str, credential: &str, body: Value) {
    assert_eq!(
        client
            .post(format!("{session}/commands"))
            .bearer_auth(credential)
            .json(&body)
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
}

#[tokio::test]
async fn provider_speed_is_published_preserved_idle_and_reset_for_work_and_generation() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let record: Value = client
        .post(format!("{}/api/v4/sessions", h.url))
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create","hostId":h.host.id,"cwd":h.cwd("token-speed")}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let id = record["id"].as_str().unwrap();
    let session = format!("{}/api/v4/sessions/{id}", h.url);
    let mut request = format!("{}/api/v4/events", h.url.replace("http://", "ws://"))
        .into_client_request()
        .unwrap();
    request.headers_mut().insert(
        "Authorization",
        format!("Bearer {credential}").parse().unwrap(),
    );
    let (mut socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
    socket
        .send(Message::Text(
            json!({"type":"subscribe","sessionId":id})
                .to_string()
                .into(),
        ))
        .await
        .unwrap();
    // The initial snapshot establishes the subscription's event fence.
    // Do not race the prompt against subscription setup.
    tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            let frame = socket.next().await.unwrap().unwrap();
            if let Message::Text(text) = frame {
                let event: Value = serde_json::from_str(&text).unwrap();
                if event["type"] == "session_snapshot" && event["sessionId"] == id {
                    assert!(event["runtime"].is_null());
                    break;
                }
            }
        }
    })
    .await
    .expect("initial session snapshot confirms the subscription");

    command(
        &client,
        &session,
        &credential,
        json!({"commandId":"sample","type":"prompt","message":"token speed"}),
    )
    .await;
    wait_operation(
        &client,
        &format!("{session}/operations/sample"),
        &credential,
        "succeeded",
    )
    .await;
    let sampled = detail(&client, &session, &credential).await;
    assert_eq!(sampled["runtime"]["state"], "idle");
    assert_eq!(sampled["runtime"]["tokensPerSecond"], 60.0);
    let generation = sampled["runtime"]["generation"].as_str().unwrap();
    tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            let frame = socket.next().await.unwrap().unwrap();
            if let Message::Text(text) = frame {
                let event: Value = serde_json::from_str(&text).unwrap();
                if matches!(
                    event["type"].as_str(),
                    Some("session_state" | "runtime_state")
                ) && event["runtime"]["tokensPerSecond"] == 60.0
                {
                    break;
                }
            }
        }
    })
    .await
    .expect("speed reaches runtime updates without requiring assistant text");

    command(&client, &session, &credential,
        json!({"commandId":"invalid","type":"prompt","message":"invalid token speed","generation":generation})).await;
    wait_operation(
        &client,
        &format!("{session}/operations/invalid"),
        &credential,
        "succeeded",
    )
    .await;
    let invalid = detail(&client, &session, &credential).await;
    assert_eq!(invalid["runtime"]["state"], "idle");
    assert!(invalid["runtime"].get("tokensPerSecond").is_none());

    command(&client, &session, &credential,
        json!({"commandId":"resample","type":"prompt","message":"token speed","generation":generation})).await;
    wait_operation(
        &client,
        &format!("{session}/operations/resample"),
        &credential,
        "succeeded",
    )
    .await;
    assert_eq!(
        detail(&client, &session, &credential).await["runtime"]["tokensPerSecond"],
        60.0
    );
    command(
        &client,
        &session,
        &credential,
        json!({"commandId":"hold","type":"prompt","message":"hold","generation":generation}),
    )
    .await;
    tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            let active = detail(&client, &session, &credential).await;
            if active["runtime"]["state"] == "running" {
                assert!(active["runtime"].get("tokensPerSecond").is_none());
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();

    command(&client, &session, &credential,
        json!({"commandId":"before-stop","type":"prompt","message":"token speed","generation":generation})).await;
    wait_operation(
        &client,
        &format!("{session}/operations/before-stop"),
        &credential,
        "succeeded",
    )
    .await;
    assert_eq!(
        detail(&client, &session, &credential).await["runtime"]["tokensPerSecond"],
        60.0
    );
    command(
        &client,
        &session,
        &credential,
        json!({"commandId":"stop","type":"stop_runtime","generation":generation}),
    )
    .await;
    wait_operation(
        &client,
        &format!("{session}/operations/stop"),
        &credential,
        "succeeded",
    )
    .await;
    assert!(detail(&client, &session, &credential).await["runtime"].is_null());
    command(
        &client,
        &session,
        &credential,
        json!({"commandId":"restart","type":"start_runtime"}),
    )
    .await;
    wait_operation(
        &client,
        &format!("{session}/operations/restart"),
        &credential,
        "succeeded",
    )
    .await;
    let restarted = detail(&client, &session, &credential).await;
    assert_ne!(restarted["runtime"]["generation"], generation);
    assert!(restarted["runtime"].get("tokensPerSecond").is_none());
}
