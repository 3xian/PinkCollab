#![cfg(feature = "test-fixtures")]

use pinkcollab_gateway::{
    events::Bus,
    storage::Store,
    v2_model::SessionRecord,
    v2_runtime::{Command, Delivery, SessionDirectory},
    workspace::Browser,
};
use std::{sync::Arc, time::Duration};

#[tokio::test]
async fn latest_prompt_replaces_title_and_survives_runtime_exit() {
    let dir = tempfile::tempdir().unwrap();
    let cwd = dir.path().join("project");
    std::fs::create_dir_all(&cwd).unwrap();
    std::fs::write(cwd.join("fixture-title.txt"), "已有会话标题").unwrap();
    let store = Arc::new(Store::open(&dir.path().join("data")).unwrap());
    let bus = Arc::new(Bus::default());
    let mut events = bus.subscribe();
    let directory = SessionDirectory::new(
        store.clone(),
        Arc::new(Browser::new(std::slice::from_ref(&cwd)).unwrap()),
        bus.clone(),
        env!("CARGO_BIN_EXE_omp-fixture").into(),
        vec![],
        1,
    );
    let now = chrono::Utc::now();
    store
        .create_v2(
            "client",
            "create",
            "create",
            SessionRecord {
                id: "session".into(),
                host_id: store.host_id().unwrap(),
                cwd: pinkcollab_gateway::workspace::display(&cwd),
                title: "project".into(),
                metadata_revision: 1,
                created_at: now,
                updated_at: now,
                archived_at: None,
                engine_session_ref: None,
            },
        )
        .unwrap();
    submit(&directory, "start", Command::StartRuntime).await;
    assert_eq!(
        directory
            .view("session")
            .await
            .unwrap()
            .unwrap()
            .session
            .title,
        "project"
    );
    let generation = directory
        .view("session")
        .await
        .unwrap()
        .unwrap()
        .runtime
        .unwrap()
        .generation;
    let first = Command::Prompt {
        delivery: Delivery::Start,
        message: "First task".into(),
        file_ids: vec![],
        expected_generation: Some(generation.clone()),
    };
    submit(&directory, "first", first.clone()).await;
    assert_eq!(
        store.v2_session("session").unwrap().unwrap().title,
        "First task"
    );
    submit(
        &directory,
        "second",
        Command::Prompt {
            delivery: Delivery::Start,
            message: "  最新消息\n继续修复模型选择器  ".into(),
            file_ids: vec![],
            expected_generation: Some(generation.clone()),
        },
    )
    .await;
    let expected = "最新消息 继续修复模型选择器";
    let revision = store
        .v2_session("session")
        .unwrap()
        .unwrap()
        .metadata_revision;
    assert_eq!(
        directory.list().await.unwrap()[0]["session"]["title"],
        expected
    );
    let (page, has_more) = directory.list_page(None, 10).await.unwrap();
    assert!(!has_more);
    assert_eq!(page[0]["session"]["title"], expected);
    assert_eq!(page[0]["runtime"]["generation"], generation);
    // An older command replay must not restore its earlier title.
    submit(&directory, "first", first).await;
    assert_eq!(
        store.v2_session("session").unwrap().unwrap().title,
        expected
    );
    assert_eq!(
        store
            .v2_session("session")
            .unwrap()
            .unwrap()
            .metadata_revision,
        revision
    );
    assert!(
        store
            .set_session_title("session", "stale-generation", "Wrong title")
            .unwrap()
            .is_none()
    );
    let mut list_updated = false;
    let mut detail_updated = false;
    while let Ok(event) = events.try_recv() {
        if event.payload["changes"][0]["value"]["session"]["title"] == expected {
            list_updated |= event.payload["resource"] == "host/sessions";
            detail_updated |= event.payload["resource"] == "session/session";
        }
    }
    assert!(list_updated && detail_updated);
    submit(
        &directory,
        "stop",
        Command::StopRuntime {
            expected_generation: generation,
        },
    )
    .await;
    let saved = store.v2_session("session").unwrap().unwrap();
    std::fs::write(
        saved.engine_session_ref.unwrap(),
        "{\"type\":\"title\",\"title\":\"Unrelated OMP title\"}\n",
    )
    .unwrap();
    directory.close().await;
    // A fresh directory has no controller to supply either metadata or operation history.
    let detached = SessionDirectory::new(
        store.clone(),
        Arc::new(Browser::new(std::slice::from_ref(&cwd)).unwrap()),
        bus,
        env!("CARGO_BIN_EXE_omp-fixture").into(),
        vec![],
        1,
    );
    let view = detached.view("session").await.unwrap().unwrap();
    assert_eq!(view.session.title, expected);
    assert!(view.runtime.is_none());
    assert!(view.messages.is_empty());
    assert_eq!(view.history_ref.as_deref(), Some("omp"));
    assert!(
        view.recent_operations
            .iter()
            .any(|operation| operation.command_id == "stop" && operation.status == "succeeded")
    );
    let expected_summary = serde_json::json!({"session":view.session,"runtime":null});
    assert_eq!(
        detached.list().await.unwrap(),
        vec![expected_summary.clone()]
    );
    let (page, has_more) = detached.list_page(None, 10).await.unwrap();
    assert!(!has_more);
    assert_eq!(page, vec![expected_summary]);
    assert!(detached.view("missing").await.unwrap().is_none());
}

async fn submit(directory: &Arc<SessionDirectory>, id: &str, command: Command) {
    assert!(
        directory
            .submit("client".into(), "session".into(), id.into(), command)
            .await
            .is_ok()
    );
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            let receipt = directory
                .operation("client", "session", id)
                .await
                .unwrap()
                .unwrap();
            match receipt.status.as_str() {
                "succeeded" => break,
                "accepted" | "dispatching" | "running" => {
                    tokio::time::sleep(Duration::from_millis(10)).await
                }
                _ => panic!(
                    "Unexpected operation status: {} {:?}",
                    receipt.status, receipt.error
                ),
            }
        }
    })
    .await
    .unwrap();
}
