use super::support::Harness;
use flate2::read::GzDecoder;
use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use std::{io::Read, time::Duration};
use tokio_tungstenite::{
    MaybeTlsStream, WebSocketStream,
    tungstenite::{Message, client::IntoClientRequest},
};

type Socket = WebSocketStream<MaybeTlsStream<tokio::net::TcpStream>>;

async fn connect(h: &Harness, token: &str, gzip: bool) -> Socket {
    let mut request = format!("{}/api/v4/events", h.url.replace("http://", "ws://"))
        .into_client_request()
        .unwrap();
    request
        .headers_mut()
        .insert("Authorization", format!("Bearer {token}").parse().unwrap());
    if gzip {
        request.headers_mut().insert(
            "Sec-WebSocket-Protocol",
            "pinkcollab.v4.gzip".parse().unwrap(),
        );
    }
    let (socket, response) = tokio_tungstenite::connect_async(request).await.unwrap();
    assert_eq!(
        response.headers().contains_key("Sec-WebSocket-Protocol"),
        gzip
    );
    socket
}

async fn next(socket: &mut Socket, gzip: bool) -> Value {
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            match socket.next().await.unwrap().unwrap() {
                Message::Text(text) => return serde_json::from_str(&text).unwrap(),
                Message::Binary(bytes) => {
                    assert!(gzip, "legacy sockets must receive text");
                    let mut json = String::new();
                    GzDecoder::new(bytes.as_ref())
                        .read_to_string(&mut json)
                        .unwrap();
                    return serde_json::from_str(&json).unwrap();
                }
                Message::Ping(bytes) => socket.send(Message::Pong(bytes)).await.unwrap(),
                other => panic!("unexpected message {other:?}"),
            }
        }
    })
    .await
    .unwrap()
}

#[tokio::test]
async fn compressed_and_text_v4_bootstrap_share_history_cursors() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let token = h.pair().await;
    let cwd = h.cwd("bootstrap");
    let mut id = String::new();
    for i in 0..8 {
        let record: Value = client
            .post(format!("{}/api/v4/sessions", h.url))
            .bearer_auth(&token)
            .json(&json!({"commandId":format!("create-{i}"),"hostId":h.host.id,"cwd":cwd}))
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        id = record["id"].as_str().unwrap().to_owned();
    }
    let path = h.dir.path().join("history.jsonl");
    let mut entries = String::new();
    for i in 0..40 {
        entries.push_str(&format!("{}\n", json!({"type":"message","id":format!("m{i}"),
            "parentId":if i==0 { None } else { Some(format!("m{}",i-1)) },
            "timestamp":"2026-01-01T00:00:00Z", "message":{"role":"user","content":[{"type":"text","text":format!("message {i}")}]}})));
    }
    std::fs::write(&path, entries).unwrap();
    assert!(
        h.store
            .set_engine_ref(
                &id,
                h.store.v2_session(&id).unwrap().unwrap().metadata_revision,
                path.to_str().unwrap()
            )
            .unwrap()
    );

    let mut compressed = connect(&h, &token, true).await;
    // The populated host snapshot exceeds the compression threshold.
    let Message::Binary(bytes) = compressed.next().await.unwrap().unwrap() else {
        panic!("expected gzip host snapshot")
    };
    let mut text = String::new();
    GzDecoder::new(bytes.as_ref())
        .read_to_string(&mut text)
        .unwrap();
    assert!(bytes.len() < text.len());
    let host: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(host["sessions"].as_array().unwrap().len(), 8);

    compressed
        .send(Message::Text(
            json!({"type":"subscribe","sessionId":id,"historyLimit":5})
                .to_string()
                .into(),
        ))
        .await
        .unwrap();
    let snapshot = next(&mut compressed, true).await;
    let page = &snapshot["history"];
    assert_eq!(page["items"].as_array().unwrap().len(), 5);
    assert!(page["source"]["id"].is_string());
    let rest: Value = client
        .get(format!("{}/api/v4/sessions/{id}/history?limit=5", h.url))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(page, &rest);
    let earlier: Value = client
        .get(format!("{}/api/v4/sessions/{id}/history", h.url))
        .query(&[("cursor", page["nextCursor"].as_str().unwrap())])
        .bearer_auth(&token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(earlier["items"].as_array().unwrap().len(), 35);

    let mut legacy = connect(&h, &token, false).await;
    assert_eq!(next(&mut legacy, false).await["type"], "host_snapshot");
    legacy
        .send(Message::Text(
            json!({"type":"subscribe","sessionId":id})
                .to_string()
                .into(),
        ))
        .await
        .unwrap();
    assert!(next(&mut legacy, false).await.get("history").is_none());

    // A huge tool/message payload must retain the normal REST fallback.
    let huge = "x".repeat(300 * 1024);
    std::fs::write(
        &path,
        format!(
            "{}\n",
            json!({"type":"message","id":"huge",
        "timestamp":"2026-01-01T00:00:00Z","message":{"role":"user",
        "content":[{"type":"text","text":huge}]}})
        ),
    )
    .unwrap();
    compressed
        .send(Message::Text(
            json!({"type":"subscribe","sessionId":id,"historyLimit":5})
                .to_string()
                .into(),
        ))
        .await
        .unwrap();
    let oversized = next(&mut compressed, true).await;
    assert_eq!(oversized["hasHistory"], true);
    assert!(oversized.get("history").is_none());

    std::fs::write(&path, "corrupt history\n").unwrap();
    compressed
        .send(Message::Text(
            json!({"type":"subscribe","sessionId":id,"historyLimit":5})
                .to_string()
                .into(),
        ))
        .await
        .unwrap();
    let unavailable = next(&mut compressed, true).await;
    assert_eq!(unavailable["hasHistory"], true);
    assert!(
        unavailable.get("history").is_none(),
        "unavailable history must fall back, never look empty"
    );
}
