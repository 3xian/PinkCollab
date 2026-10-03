#[path = "protocol/startup_transport.rs"]
mod startup_transport;
mod support;
#[path = "protocol/usage.rs"]
mod usage;

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
    let mut request = format!("{}/api/v4/events", h.url.replace("http://", "ws://"))
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
        .post(format!("{}/api/v4/sessions", h.url))
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
    let session = format!("{}/api/v4/sessions/{id}", h.url);
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
        matches!(v["type"].as_str(), Some("session_state" | "runtime_state"))
            && v["runtime"].is_null()
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
    let session = format!("{}/api/v4/sessions/{id}", h.url);
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
async fn backlog_recovers_without_disconnect_and_oversized_event_closes_socket() {
    let h = Harness::new(1, vec![]).await;
    let credential = h.pair().await;
    let mut socket = connect(&h, &credential).await;
    next(&mut socket).await;
    for _ in 0..100 {
        h.bus.publish(ServerEvent::Timeline {
            session_id: "unsubscribed".into(),
            upsert: vec![],
            remove: vec![],
            reset: false,
        });
    }
    socket
        .send(Message::Ping(vec![1, 2, 3].into()))
        .await
        .unwrap();
    tokio::time::timeout(Duration::from_secs(3), async {
        loop {
            match socket.next().await.unwrap().unwrap() {
                Message::Pong(data) => {
                    assert_eq!(data.as_ref(), &[1, 2, 3]);
                    break;
                }
                Message::Ping(data) => socket.send(Message::Pong(data)).await.unwrap(),
                Message::Close(_) => panic!("backlog disconnected a healthy scope"),
                _ => {}
            }
        }
    })
    .await
    .unwrap();
    h.bus.publish(ServerEvent::Timeline {
        session_id: "example".into(),
        upsert: vec![],
        remove: vec!["x".repeat(9 * 1024 * 1024)],
        reset: false,
    });
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

#[tokio::test]
async fn old_clients_receive_explicit_upgrade_error() {
    let h = Harness::new(1, vec![]).await;
    for path in [
        "/api/v1/host",
        "/api/v2/events",
        "/api/v2/pair",
        "/api/v3/events",
    ] {
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
    let session = format!("{}/api/v4/sessions/{id}", h.url);
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

#[tokio::test]
async fn unchanged_reconnect_resumes_host_and_session_without_transcript_or_receipts() {
    let h = Harness::new(1, vec![]).await;
    let credential = h.pair().await;
    let id = create(&h, &reqwest::Client::new(), &credential, &h.cwd("resume")).await;
    let mut socket = connect(&h, &credential).await;
    let host = next(&mut socket).await;
    socket
        .send(Message::Text(
            json!({"type":"subscribe","sessionId":id})
                .to_string()
                .into(),
        ))
        .await
        .unwrap();
    let detail = next(&mut socket).await;
    socket.close(None).await.unwrap();
    let mut request = format!(
        "{}/api/v4/events?epoch={}&sequence={}&catalog={}",
        h.url.replace("http://", "ws://"),
        host["epoch"].as_str().unwrap(),
        host["sequence"],
        host["catalogVersion"].as_str().unwrap()
    )
    .into_client_request()
    .unwrap();
    request.headers_mut().insert(
        "Authorization",
        format!("Bearer {credential}").parse().unwrap(),
    );
    let mut resumed = tokio_tungstenite::connect_async(request).await.unwrap().0;
    assert_eq!(next(&mut resumed).await["type"], "host_sync");
    resumed.send(Message::Text(json!({"type":"subscribe","sessionId":id,"epoch":detail["epoch"],"sequence":detail["sequence"],"hasCachedHistory":true}).to_string().into())).await.unwrap();
    assert_eq!(next(&mut resumed).await["type"], "session_resume");
    let synced = next(&mut resumed).await;
    assert_eq!(synced["type"], "session_sync");
    assert_eq!(synced["timeline"], json!([]));
    assert_eq!(synced["operations"], json!([]));
    assert!(synced.get("history").is_none());
}

#[tokio::test]
async fn streaming_reply_uses_append_frames_with_linear_wire_bytes() {
    use sha2::{Digest, Sha256};
    let h = Harness::new(1, vec![]).await;
    let credential = h.pair().await;
    let client = reqwest::Client::new();
    let id = create(&h, &client, &credential, &h.cwd("stream")).await;
    let mut socket = connect(&h, &credential).await;
    next(&mut socket).await;
    socket
        .send(Message::Text(
            json!({"type":"subscribe","sessionId":id})
                .to_string()
                .into(),
        ))
        .await
        .unwrap();
    next(&mut socket).await;
    post(
        &client,
        &format!("{}/api/v4/sessions/{id}", h.url),
        &credential,
        json!({"commandId":"stream","type":"prompt","message":"stream reply"}),
    )
    .await;
    let mut reconstructed = String::new();
    let mut patches = 0;
    let mut wire_bytes = 0;
    let mut cumulative_bytes = 0;
    loop {
        let frame = next(&mut socket).await;
        if frame["type"] == "timeline" {
            for item in frame["upsert"].as_array().unwrap() {
                if item["kind"] == "assistant" {
                    reconstructed = item["text"].as_str().unwrap().into();
                    wire_bytes += serde_json::to_vec(item).unwrap().len();
                }
            }
        } else if frame["type"] == "message_patch" {
            assert_eq!(
                frame["baseHash"],
                hex::encode(Sha256::digest(reconstructed.as_bytes()))
            );
            reconstructed.push_str(frame["append"].as_str().unwrap());
            assert_eq!(
                frame["hash"],
                hex::encode(Sha256::digest(reconstructed.as_bytes()))
            );
            wire_bytes += serde_json::to_vec(&frame).unwrap().len();
            cumulative_bytes += reconstructed.len();
            patches += 1;
        }
        if frame["operation"]["id"] == "stream" && frame["operation"]["state"] == "succeeded" {
            break;
        }
    }
    let expected = format!("stream-fixture:{}", "abcdef0123456789".repeat(8192));
    assert_eq!(reconstructed, expected);
    assert!(patches > 20);
    assert!(wire_bytes < expected.len() * 2);
    assert!(wire_bytes * 10 < cumulative_bytes);
    eprintln!(
        "stream fixture: {patches} patches, {wire_bytes} JSON bytes vs {cumulative_bytes} cumulative body bytes"
    );
}

#[tokio::test]
async fn catalog_bootstrap_cursor_loads_every_older_session_once() {
    use pinkcollab_gateway::domain::SessionRecord;
    let h = Harness::new(1, vec![]).await;
    let credential = h.pair().await;
    let now = chrono::Utc::now();
    for index in 0..70 {
        h.store
            .create_v2(
                "client",
                &format!("create-{index}"),
                &format!("fp-{index}"),
                SessionRecord {
                    id: format!("s{index}"),
                    host_id: h.host.id.clone(),
                    cwd: h.cwd("catalog"),
                    title: format!("session {index}"),
                    metadata_revision: 1,
                    created_at: now - chrono::Duration::seconds(index),
                    updated_at: now,
                    archived_at: None,
                    engine_session_ref: None,
                },
            )
            .unwrap();
    }
    let mut socket = connect(&h, &credential).await;
    let first = next(&mut socket).await;
    assert_eq!(first["totalSessions"], 70);
    assert_eq!(first["sessions"].as_array().unwrap().len(), 50);
    let later: Value = reqwest::Client::new()
        .get(format!("{}/api/v4/sessions", h.url))
        .bearer_auth(&credential)
        .query(&[("cursor", first["nextSessionsCursor"].as_str().unwrap())])
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let mut ids = std::collections::HashSet::new();
    for session in first["sessions"]
        .as_array()
        .unwrap()
        .iter()
        .chain(later["sessions"].as_array().unwrap())
    {
        assert!(ids.insert(session["session"]["id"].as_str().unwrap().to_owned()));
    }
    assert_eq!(ids.len(), 70);
    assert!(later["nextCursor"].is_null());
}

#[tokio::test]
async fn catalog_change_without_a_bus_event_invalidates_only_host_resume() {
    use pinkcollab_gateway::domain::SessionRecord;
    let h = Harness::new(1, vec![]).await;
    let credential = h.pair().await;
    let mut socket = connect(&h, &credential).await;
    let old = next(&mut socket).await;
    socket.close(None).await.unwrap();
    let now = chrono::Utc::now();
    h.store
        .create_v2(
            "client",
            "quiet",
            "quiet",
            SessionRecord {
                id: "quiet".into(),
                host_id: h.host.id.clone(),
                cwd: h.cwd("quiet"),
                title: "newly discovered metadata".into(),
                metadata_revision: 1,
                created_at: now,
                updated_at: now,
                archived_at: None,
                engine_session_ref: None,
            },
        )
        .unwrap();
    let mut request = format!(
        "{}/api/v4/events?epoch={}&sequence={}&catalog={}",
        h.url.replace("http://", "ws://"),
        old["epoch"].as_str().unwrap(),
        old["sequence"],
        old["catalogVersion"].as_str().unwrap()
    )
    .into_client_request()
    .unwrap();
    request.headers_mut().insert(
        "Authorization",
        format!("Bearer {credential}").parse().unwrap(),
    );
    let mut resumed = tokio_tungstenite::connect_async(request).await.unwrap().0;
    let fresh = next(&mut resumed).await;
    assert_eq!(fresh["type"], "host_snapshot");
    assert_eq!(fresh["totalSessions"], 1);
    assert_eq!(fresh["sessions"][0]["session"]["id"], "quiet");
}
