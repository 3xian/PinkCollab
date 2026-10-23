#![cfg(feature = "test-fixtures")]
mod support;

use pinkcollab_gateway::{events::Event, protocol::ServerEvent, workspace};
use serde_json::{Value, json};
use support::{Harness, command_succeeds, create_session, runtime_generation};

#[tokio::test]
async fn stop_survives_unreadable_session_records_and_unwritable_receipts() {
    let harness = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = harness.pair().await;
    let session = create_session(&client, &harness, &credential, &harness.cwd("offline")).await;
    let id = session.rsplit('/').next().unwrap();
    command_succeeds(
        &client,
        &session,
        &credential,
        "start",
        json!({"type":"start_runtime"}),
    )
    .await;
    let generation = runtime_generation(&client, &session, &credential).await;
    let db = rusqlite::Connection::open(harness.dir.path().join("data/pinkcollab.db")).unwrap();
    db.execute_batch(
        "ALTER TABLE session_records RENAME TO hidden_sessions;
        ALTER TABLE operation_records RENAME TO hidden_operations;",
    )
    .unwrap();
    let ordinary = client
        .post(format!("{session}/commands"))
        .bearer_auth(&credential)
        .json(&json!({"commandId":"ordinary","type":"interrupt","generation":generation}))
        .send()
        .await
        .unwrap();
    assert_eq!(ordinary.status(), 503);
    let mut events = harness.bus.subscribe();
    let response = client
        .post(format!("{session}/commands"))
        .bearer_auth(&credential)
        .json(&json!({"commandId":"emergency","type":"stop_runtime","generation":generation}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 202);
    assert_eq!(
        response.json::<Value>().await.unwrap()["receiptStored"],
        false
    );
    tokio::time::timeout(std::time::Duration::from_secs(8), async {
        loop {
            if let Event::Update { event, .. } = events.recv().await.unwrap()
                && matches!(event.as_ref(), ServerEvent::SessionState { session_id, summary, .. }
                    if session_id == id && summary.runtime.is_none())
            {
                break;
            }
        }
    })
    .await
    .unwrap();
    db.execute_batch(
        "ALTER TABLE hidden_sessions RENAME TO session_records;
        ALTER TABLE hidden_operations RENAME TO operation_records;",
    )
    .unwrap();
}

#[tokio::test]
async fn legacy_shared_history_is_indexed_and_neither_session_can_delete_it() {
    let files = tempfile::tempdir().unwrap();
    let args = vec!["--session-dir".into(), workspace::display(files.path())];
    let mut harness = Harness::new(1, args.clone()).await;
    let client = reqwest::Client::new();
    let credential = harness.pair().await;
    let session = create_session(&client, &harness, &credential, &harness.cwd("shared")).await;
    let id = session.rsplit('/').next().unwrap().to_owned();
    let mut sibling = harness.store.v2_session(&id).unwrap().unwrap();
    sibling.id = pinkcollab_gateway::storage::id("sess_");
    let other_id = sibling.id.clone();
    harness
        .store
        .create_v2("client", "sibling", "sibling", sibling)
        .unwrap();
    let path = files.path().join("shared.jsonl");
    std::fs::write(&path, "shared transcript").unwrap();
    // Simulate a pre-index database whose two mappings point at the same file.
    let db = rusqlite::Connection::open(harness.dir.path().join("data/pinkcollab.db")).unwrap();
    db.execute(
        "UPDATE session_records SET engine_session_ref=?1",
        [workspace::display(&path)],
    )
    .unwrap();
    db.execute_batch("PRAGMA user_version=8").unwrap();
    drop(db);
    harness.restart(1, args).await;
    for id in [&id, &other_id] {
        assert_eq!(
            client
                .delete(format!("{}/api/v4/sessions/{id}", harness.url))
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .status(),
            503
        );
        assert!(harness.store.v2_session(id).unwrap().is_some());
    }
    assert_eq!(std::fs::read_to_string(path).unwrap(), "shared transcript");
}

#[tokio::test]
async fn deleting_history_does_not_read_unrelated_session_records() {
    let files = tempfile::tempdir().unwrap();
    let harness = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = harness.pair().await;
    let session = create_session(&client, &harness, &credential, &harness.cwd("isolated")).await;
    let id = session.rsplit('/').next().unwrap();
    let mut sibling = harness.store.v2_session(id).unwrap().unwrap();
    sibling.id = pinkcollab_gateway::storage::id("sess_");
    let other_id = sibling.id.clone();
    harness
        .store
        .create_v2("client", "sibling", "sibling", sibling)
        .unwrap();
    let path = files.path().join("history.jsonl");
    std::fs::write(&path, "transcript").unwrap();
    harness
        .store
        .set_engine_ref(id, 1, &workspace::display(&path))
        .unwrap();
    let db = rusqlite::Connection::open(harness.dir.path().join("data/pinkcollab.db")).unwrap();
    db.execute(
        "UPDATE session_records SET created_at='unreadable' WHERE id=?1",
        [&other_id],
    )
    .unwrap();
    assert_eq!(
        client
            .delete(&session)
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .status(),
        204
    );
    assert!(!path.exists());
    let upload = client
        .put(format!(
            "{session}/files/file_0123456789abcdef0123456789abcdef?name=note.txt"
        ))
        .bearer_auth(&credential)
        .body("late upload")
        .send()
        .await
        .unwrap();
    assert_eq!(upload.status(), 404);
    assert!(!harness.store.uploads_dir().join(id).exists());
}

#[tokio::test]
async fn deleting_managed_history_requires_exit_and_cleans_files_records_and_catalog() {
    let files = tempfile::tempdir().unwrap();
    let mut harness = Harness::new(
        1,
        vec!["--session-dir".into(), workspace::display(files.path())],
    )
    .await;
    let client = reqwest::Client::new();
    let credential = harness.pair().await;
    let cwd = harness.cwd("delete");
    let session = create_session(&client, &harness, &credential, &cwd).await;
    let id = session.rsplit('/').next().unwrap();
    assert_eq!(client.delete(&session).send().await.unwrap().status(), 401);
    command_succeeds(
        &client,
        &session,
        &credential,
        "start",
        json!({"type":"start_runtime"}),
    )
    .await;
    let blocked = client
        .delete(&session)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap();
    assert_eq!(blocked.status(), 409);
    assert_eq!(
        blocked.json::<Value>().await.unwrap()["code"],
        "session_busy"
    );
    let generation = runtime_generation(&client, &session, &credential).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "stop",
        json!({"type":"stop_runtime","generation":generation}),
    )
    .await;
    let record = harness.store.v2_session(id).unwrap().unwrap();
    let reference = std::path::Path::new(record.engine_session_ref.as_ref().unwrap());
    let artifacts = reference.with_extension("");
    std::fs::create_dir_all(artifacts.join("child")).unwrap();
    std::fs::write(artifacts.join("child/result.log"), "output").unwrap();
    let upload = harness.store.uploads_dir().join(id).join("attachment");
    std::fs::create_dir_all(&upload).unwrap();
    std::fs::write(upload.join("image.png"), "image").unwrap();
    let project = std::path::Path::new(&cwd).join("code.txt");
    std::fs::write(&project, "keep").unwrap();
    let mut events = harness.bus.subscribe();
    assert_eq!(
        client
            .delete(&session)
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .status(),
        204
    );
    assert!(!reference.exists());
    assert!(!artifacts.exists());
    assert!(!harness.store.uploads_dir().join(id).exists());
    assert_eq!(std::fs::read_to_string(project).unwrap(), "keep");
    assert!(harness.store.v2_session(id).unwrap().is_none());
    assert!(harness.store.recent_operations(id, 100).unwrap().is_empty());
    assert!(
        matches!(events.recv().await.unwrap(), Event::Update { event, .. }
        if matches!(event.as_ref(), ServerEvent::SessionRemove { session_id } if session_id == id))
    );
    assert_eq!(
        client
            .get(&session)
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .status(),
        404
    );
    assert_eq!(
        client
            .delete(&session)
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .status(),
        204
    );
    let replay = client
        .post(format!("{}/api/v4/sessions", harness.url))
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create","hostId":harness.host.id,"cwd":cwd}))
        .send()
        .await
        .unwrap();
    assert_eq!(replay.status(), 410);
    harness
        .restart(
            1,
            vec!["--session-dir".into(), workspace::display(files.path())],
        )
        .await;
    let catalog: Value = client
        .get(format!("{}/api/v4/sessions", harness.url))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(catalog["sessions"].as_array().unwrap().is_empty());
}

#[tokio::test]
async fn discovered_deletion_rejects_external_writer_and_is_retryable_after_file_failure() {
    let files = tempfile::tempdir().unwrap();
    let mut harness = Harness::new(
        1,
        vec!["--session-dir".into(), workspace::display(files.path())],
    )
    .await;
    let cwd = harness.cwd("external");
    let path = files.path().join("external.jsonl");
    std::fs::write(&path, format!("{}\n", json!({"type":"session","version":3,"id":"external","cwd":cwd,"timestamp":"2026-01-01T00:00:00Z"}))).unwrap();
    let client = reqwest::Client::new();
    let credential = harness.pair().await;
    let list: Value = client
        .get(format!("{}/api/v4/sessions", harness.url))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let id = list["sessions"][0]["session"]["id"].as_str().unwrap();
    let session = format!("{}/api/v4/sessions/{id}", harness.url);
    let lock = files.path().join(".external.jsonl.lock");
    std::fs::write(&lock, "writer").unwrap();
    let blocked = client
        .delete(&session)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap();
    assert_eq!(blocked.status(), 409);
    assert_eq!(
        blocked.json::<Value>().await.unwrap()["code"],
        "external_session_busy"
    );
    assert!(path.exists());
    std::fs::remove_file(lock).unwrap();
    // An invalid artifact directory forces a file error after durable adoption.
    let artifacts = path.with_extension("");
    std::fs::write(&artifacts, "not a directory").unwrap();
    assert_eq!(
        client
            .delete(&session)
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .status(),
        503
    );
    assert!(path.exists());
    assert!(harness.store.v2_session(id).unwrap().is_some());
    harness
        .restart(
            1,
            vec!["--session-dir".into(), workspace::display(files.path())],
        )
        .await;
    let session = format!("{}/api/v4/sessions/{id}", harness.url);
    std::fs::remove_file(&artifacts).unwrap();
    assert_eq!(
        client
            .delete(&session)
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .status(),
        204
    );
    assert!(!path.exists());
    assert!(harness.store.adopted_omp_id(id).unwrap().is_none());
}

#[tokio::test]
async fn missing_expected_history_and_stale_runtime_leases_remain_safe_to_handle() {
    let files = tempfile::tempdir().unwrap();
    let harness = Harness::new(
        1,
        vec!["--session-dir".into(), workspace::display(files.path())],
    )
    .await;
    let client = reqwest::Client::new();
    let credential = harness.pair().await;
    let session = create_session(&client, &harness, &credential, &harness.cwd("missing")).await;
    let id = session.rsplit('/').next().unwrap();
    harness.store.reserve_runtime(id, "old-generation").unwrap();
    assert_eq!(
        client
            .delete(&session)
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .status(),
        409
    );
    harness.store.release_runtime(id, "old-generation").unwrap();
    let missing = workspace::display(&files.path().join("absent/directory/history.jsonl"));
    harness.store.set_engine_ref(id, 1, &missing).unwrap();
    let parent = std::path::Path::new(&missing).parent().unwrap();
    std::fs::create_dir_all(parent).unwrap();
    let writer = parent.join(".history.jsonl.lock");
    std::fs::write(&writer, "writer creating history").unwrap();
    let blocked = client
        .delete(&session)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap();
    assert_eq!(blocked.status(), 409);
    assert_eq!(
        blocked.json::<Value>().await.unwrap()["code"],
        "external_session_busy"
    );
    std::fs::remove_file(writer).unwrap();
    std::fs::remove_dir(parent).unwrap();
    assert_eq!(
        client
            .delete(&session)
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .status(),
        204
    );
    let start = client
        .post(format!("{session}/commands"))
        .bearer_auth(&credential)
        .json(&json!({"commandId":"late","type":"start_runtime"}))
        .send()
        .await
        .unwrap();
    assert_eq!(start.status(), 404);
}
