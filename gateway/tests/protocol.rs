mod support;

use futures_util::{SinkExt, StreamExt};
use pinkcollab_gateway::protocol::ServerEvent;
use serde_json::{Value, json};
use std::time::Duration;
use support::{Harness, wait_operation, wait_runtime};
use tokio_tungstenite::{
    MaybeTlsStream, WebSocketStream,
    tungstenite::{Message, client::IntoClientRequest},
};

type Socket = WebSocketStream<MaybeTlsStream<tokio::net::TcpStream>>;
async fn connect(h: &Harness, credential: &str) -> Socket {
    let mut request = format!("{}/api/v3/events", h.url.replace("http://", "ws://"))
        .into_client_request()
        .unwrap();
    request.headers_mut().insert(
        "Authorization",
        format!("Bearer {credential}").parse().unwrap(),
    );
    tokio_tungstenite::connect_async(request).await.unwrap().0
}
async fn next(socket: &mut Socket) -> Value {
    tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            match socket.next().await.unwrap().unwrap() {
                Message::Text(text) => return serde_json::from_str(&text).unwrap(),
                Message::Ping(data) => socket.send(Message::Pong(data)).await.unwrap(),
                Message::Close(reason) => panic!("unexpected close: {reason:?}"),
                _ => {}
            }
        }
    })
    .await
    .unwrap()
}
async fn until(socket: &mut Socket, predicate: impl Fn(&Value) -> bool) -> Value {
    tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            let event = next(socket).await;
            if predicate(&event) {
                return event;
            }
        }
    })
    .await
    .unwrap()
}
async fn post(client: &reqwest::Client, session: &str, credential: &str, body: Value) {
    let response = client
        .post(format!("{session}/commands"))
        .bearer_auth(credential)
        .json(&body)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 202, "{}", response.text().await.unwrap());
}
async fn create(h: &Harness, client: &reqwest::Client, credential: &str, cwd: &str) -> String {
    let record: Value = client
        .post(format!("{}/api/v3/sessions", h.url))
        .bearer_auth(credential)
        .json(&json!({"commandId":"create","hostId":h.host.id,"cwd":cwd}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(record.get("metadataRevision").is_none());
    assert!(record.get("archivedAt").is_none());
    record["id"].as_str().unwrap().into()
}

#[tokio::test]
async fn socket_lifecycle_receipts_and_reconnect_are_authoritative() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let mut socket = connect(&h, &credential).await;
    assert_eq!(next(&mut socket).await["type"], "host_snapshot");
    let id = create(&h, &client, &credential, &h.cwd("lifecycle")).await;
    assert_eq!(next(&mut socket).await["type"], "session_upsert");
    let session = format!("{}/api/v3/sessions/{id}", h.url);
    socket
        .send(Message::Text(
            json!({"type":"subscribe","sessionId":id})
                .to_string()
                .into(),
        ))
        .await
        .unwrap();
    let initial = next(&mut socket).await;
    assert_eq!(initial["type"], "session_snapshot");
    assert!(initial["runtime"].is_null());
    post(
        &client,
        &session,
        &credential,
        json!({"commandId":"start","type":"start_runtime"}),
    )
    .await;
    let pending = until(&mut socket, |v| {
        v["type"] == "operation" && v["operation"]["state"] == "pending"
    })
    .await;
    assert_eq!(pending["operation"].as_object().unwrap().len(), 4);
    until(&mut socket, |v| v["runtime"]["state"] == "starting").await;
    let idle = until(&mut socket, |v| v["runtime"]["state"] == "idle").await;
    assert!(idle["runtime"].get("phase").is_none());
    assert!(idle["runtime"].get("execution").is_none());
    let generation = idle["runtime"]["generation"].clone();
    until(&mut socket, |v| {
        v["operation"]["id"] == "start" && v["operation"]["state"] == "succeeded"
    })
    .await;
    post(
        &client,
        &session,
        &credential,
        json!({"commandId":"input","type":"prompt","generation":generation,"message":"need input"}),
    )
    .await;
    until(&mut socket, |v| v["runtime"]["state"] == "running").await;
    until(&mut socket, |v| v["runtime"]["state"] == "waiting_input").await;
    post(&client,&session,&credential,json!({"commandId":"answer","type":"respond","generation":generation,"inputRequestId":"question-1","value":"new"})).await;
    until(&mut socket, |v| v["runtime"]["state"] == "idle").await;
    post(
        &client,
        &session,
        &credential,
        json!({"commandId":"fail","type":"prompt","generation":generation,"message":"fail"}),
    )
    .await;
    until(&mut socket, |v| {
        v["operation"]["id"] == "fail" && v["operation"]["state"] == "pending"
    })
    .await;
    until(&mut socket, |v| {
        v["operation"]["id"] == "fail" && v["operation"]["state"] == "failed"
    })
    .await;
    post(
        &client,
        &session,
        &credential,
        json!({"commandId":"stop","type":"stop_runtime","generation":generation}),
    )
    .await;
    until(&mut socket, |v| v["runtime"]["state"] == "stopping").await;
    until(&mut socket, |v| {
        v["type"] == "session_state" && v["runtime"].is_null()
    })
    .await;
    until(&mut socket, |v| {
        v["type"] == "timeline" && v["reset"] == true
    })
    .await;
    wait_operation(
        &client,
        &format!("{session}/operations/stop"),
        &credential,
        "succeeded",
    )
    .await;
    socket.close(None).await.unwrap();
    let mut fresh = connect(&h, &credential).await;
    assert!(next(&mut fresh).await["sessions"][0]["runtime"].is_null());
    fresh
        .send(Message::Text(
            json!({"type":"subscribe","sessionId":id})
                .to_string()
                .into(),
        ))
        .await
        .unwrap();
    let detail = next(&mut fresh).await;
    assert_eq!(detail["timeline"], json!([]));
    assert!(
        detail["operations"]
            .as_array()
            .unwrap()
            .iter()
            .any(|o| o["id"] == "stop" && o["state"] == "succeeded")
    );
}

#[tokio::test]
async fn prompt_routing_uses_gateway_state_and_missing_generation_cannot_attach() {
    let h = Harness::new(1, vec!["--record-prompt-frame".into()]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let cwd = h.cwd("routing");
    let id = create(&h, &client, &credential, &cwd).await;
    let session = format!("{}/api/v3/sessions/{id}", h.url);
    post(
        &client,
        &session,
        &credential,
        json!({"commandId":"hold","type":"prompt","message":"hold"}),
    )
    .await;
    let runtime = wait_runtime(&client, &session, &credential, "running").await;
    let generation = &runtime["generation"];
    post(
        &client,
        &session,
        &credential,
        json!({"commandId":"stale","type":"prompt","message":"must not dispatch"}),
    )
    .await;
    let rejected = wait_operation(
        &client,
        &format!("{session}/operations/stale"),
        &credential,
        "failed",
    )
    .await;
    assert_eq!(rejected["error"]["code"], "generation_mismatch");
    post(
        &client,
        &session,
        &credential,
        json!({"commandId":"steer","type":"prompt","generation":generation,"message":"steer"}),
    )
    .await;
    wait_operation(
        &client,
        &format!("{session}/operations/steer"),
        &credential,
        "succeeded",
    )
    .await;
    let frame: Value = serde_json::from_slice(
        &std::fs::read(std::path::Path::new(&cwd).join("last-prompt.json")).unwrap(),
    )
    .unwrap();
    assert_eq!(frame["streamingBehavior"], "steer");
    post(
        &client,
        &session,
        &credential,
        json!({"commandId":"idle","type":"prompt","generation":generation,"message":"hello"}),
    )
    .await;
    wait_operation(
        &client,
        &format!("{session}/operations/idle"),
        &credential,
        "succeeded",
    )
    .await;
    let frame: Value = serde_json::from_slice(
        &std::fs::read(std::path::Path::new(&cwd).join("last-prompt.json")).unwrap(),
    )
    .unwrap();
    assert!(frame.get("streamingBehavior").is_none());
    post(
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
}

#[tokio::test]
async fn broadcast_backlog_and_oversized_event_close_socket_then_reconnect() {
    let h = Harness::new(1, vec![]).await;
    let credential = h.pair().await;
    for oversized in [false, true] {
        let mut socket = connect(&h, &credential).await;
        assert_eq!(next(&mut socket).await["type"], "host_snapshot");
        // Current-thread runtime cannot drain the receiver during this synchronous burst.
        if oversized {
            h.bus.publish(ServerEvent::Timeline {
                session_id: "example".into(),
                upsert: vec![],
                remove: vec!["x".repeat(300 * 1024)],
                reset: false,
            });
        } else {
            for _ in 0..100 {
                h.bus.publish(ServerEvent::Timeline {
                    session_id: "example".into(),
                    upsert: vec![],
                    remove: vec![],
                    reset: false,
                });
            }
        }
        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                match socket.next().await {
                    Some(Ok(Message::Ping(v))) => socket.send(Message::Pong(v)).await.unwrap(),
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                    _ => {}
                }
            }
        })
        .await
        .unwrap();
    }
}

#[tokio::test]
async fn old_clients_receive_explicit_upgrade_error() {
    let h = Harness::new(1, vec![]).await;
    for path in ["/api/v1/host", "/api/v2/events", "/api/v2/pair"] {
        let response = reqwest::Client::new()
            .get(format!("{}{path}", h.url))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 426);
        assert_eq!(
            response.json::<Value>().await.unwrap()["code"],
            "protocol_upgrade_required"
        );
    }
}

#[tokio::test]
async fn prompt_rejects_unknown_execution_after_startup_and_when_attached() {
    let h = Harness::new(1, vec!["--unknown-execution".into()]).await;
    let credential = h.pair().await;
    let client = reqwest::Client::new();
    let cwd = h.cwd("unknown");
    let id = create(&h, &client, &credential, &cwd).await;
    let session = format!("{}/api/v3/sessions/{id}", h.url);
    for (command, generation) in [("new", None), ("attached", Some(()))] {
        let mut body = json!({"commandId":command,"type":"prompt","message":"must not send"});
        if generation.is_some() {
            body["generation"] =
                wait_runtime(&client, &session, &credential, "starting").await["generation"]
                    .clone();
        }
        post(&client, &session, &credential, body).await;
        let receipt = wait_operation(
            &client,
            &format!("{session}/operations/{command}"),
            &credential,
            "failed",
        )
        .await;
        assert_eq!(receipt["error"]["code"], "runtime_not_ready");
        assert!(!std::path::Path::new(&cwd).join("last-prompt.json").exists());
    }
    let generation =
        wait_runtime(&client, &session, &credential, "starting").await["generation"].clone();
    post(
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
}
