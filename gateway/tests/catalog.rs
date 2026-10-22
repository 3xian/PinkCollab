mod support;

use futures_util::{SinkExt, StreamExt};
use pinkcollab_gateway::domain::SessionRecord;
use serde_json::{Value, json};
use std::time::{Duration, SystemTime};
use support::Harness;
use tokio_tungstenite::tungstenite::{Message, client::IntoClientRequest};

fn seed(h: &Harness) {
    let now = chrono::Utc::now();
    for index in 0..60 {
        let stamp = now - chrono::Duration::seconds(index);
        let id = format!("s{index}");
        h.store
            .create_v2(
                "c",
                &id.clone(),
                "fp",
                SessionRecord {
                    id,
                    host_id: h.host.id.clone(),
                    cwd: h.cwd("catalog"),
                    title: "task".into(),
                    metadata_revision: 1,
                    created_at: stamp,
                    updated_at: stamp,
                    last_activity_at: stamp,
                    archived_at: None,
                    engine_session_ref: None,
                },
            )
            .unwrap();
    }
}

async fn snapshot(h: &Harness, credential: &str, query: &str) -> Value {
    let mut request = format!(
        "{}/api/v4/events?{query}",
        h.url.replace("http://", "ws://")
    )
    .into_client_request()
    .unwrap();
    request.headers_mut().insert(
        "Authorization",
        format!("Bearer {credential}").parse().unwrap(),
    );
    let mut socket = tokio_tungstenite::connect_async(request).await.unwrap().0;
    let value = tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            match socket.next().await.unwrap().unwrap() {
                Message::Text(raw) => break serde_json::from_str(&raw).unwrap(),
                Message::Ping(data) => socket.send(Message::Pong(data)).await.unwrap(),
                other => panic!("unexpected bootstrap frame: {other:?}"),
            }
        }
    })
    .await
    .unwrap();
    socket.close(None).await.unwrap();
    value
}

#[tokio::test]
async fn focused_bootstrap_and_paging_do_not_decode_detail_receipts() {
    let files = tempfile::tempdir().unwrap();
    let h = Harness::new(
        1,
        vec![
            "--session-dir".into(),
            files.path().to_string_lossy().into_owned(),
        ],
    )
    .await;
    seed(&h);
    let credential = h.pair().await;
    let db = rusqlite::Connection::open(h.dir.path().join("data/pinkcollab.db")).unwrap();
    db.execute("INSERT INTO operation_records (client_id,session_id,command_id,command_type,request_fingerprint,status,result,created_at,updated_at)
        VALUES ('c','s59','op','prompt','fp','succeeded','not-json',?1,?1)", [chrono::Utc::now().to_rfc3339()]).unwrap();
    let first = snapshot(&h, &credential, "focus=s59").await;
    assert_eq!(first["type"], "host_snapshot");
    assert_eq!(first["totalSessions"], 60);
    assert!(
        first["sessions"]
            .as_array()
            .unwrap()
            .iter()
            .any(|s| s["session"]["id"] == "s59")
    );
    let page: Value = reqwest::Client::new()
        .get(format!("{}/api/v4/sessions", h.url))
        .bearer_auth(&credential)
        .query(&[("cursor", first["nextSessionsCursor"].as_str().unwrap())])
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(page["sessions"].as_array().unwrap().len(), 10);
    assert_eq!(page["catalogVersion"], first["catalogVersion"]);
    assert!(page["nextCursor"].is_null());
    // The corrupted detail still fails explicitly; list reads never consult it.
    assert_eq!(
        reqwest::Client::new()
            .get(format!("{}/api/v4/sessions/s59", h.url))
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .status(),
        503
    );
}

#[tokio::test]
async fn http_page_identifies_its_discovery_generation_when_catalog_returns_to_old_version() {
    let files = tempfile::tempdir().unwrap();
    let h = Harness::new(
        1,
        vec![
            "--session-dir".into(),
            files.path().to_string_lossy().into_owned(),
        ],
    )
    .await;
    seed(&h);
    let credential = h.pair().await;
    let initial = snapshot(&h, &credential, "").await;
    let path = files.path().join("temporary.jsonl");
    std::fs::write(&path, format!("{}\n", json!({"type":"session","version":3,"id":"temporary","cwd":h.cwd("catalog"),"timestamp":"2000-01-01T00:00:00Z"}))).unwrap();
    // Windows requires write access to change file timestamps.
    std::fs::OpenOptions::new()
        .write(true)
        .open(&path)
        .unwrap()
        .set_times(
            std::fs::FileTimes::new()
                .set_modified(SystemTime::UNIX_EPOCH + Duration::from_secs(946684800)),
        )
        .unwrap();
    // A second connection refreshes the shared Discovery cache without bus events.
    let temporary = snapshot(&h, &credential, "refresh=1").await;
    assert_eq!(temporary["totalSessions"], 61);
    let page: Value = reqwest::Client::new()
        .get(format!("{}/api/v4/sessions", h.url))
        .bearer_auth(&credential)
        .query(&[("cursor", initial["nextSessionsCursor"].as_str().unwrap())])
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(
        page["sessions"]
            .as_array()
            .unwrap()
            .iter()
            .any(|s| s["session"]["origin"] == "discovered")
    );
    assert_eq!(page["catalogVersion"], temporary["catalogVersion"]);
    assert_ne!(page["catalogVersion"], initial["catalogVersion"]);
    std::fs::remove_file(path).unwrap();
    let restored = snapshot(&h, &credential, "refresh=1").await;
    assert_eq!(restored["catalogVersion"], initial["catalogVersion"]);
    assert_ne!(restored["catalogVersion"], page["catalogVersion"]);
    assert_eq!(restored["sequence"], initial["sequence"]);
}
