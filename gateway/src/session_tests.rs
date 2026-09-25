use super::*;
use rusqlite::Connection;
use std::time::Duration;

#[tokio::test]
async fn metadata_is_visible_only_after_commit_and_rolls_back_on_failure() {
    let dir = tempfile::tempdir().unwrap();
    let db_dir = dir.path().join("data");
    let project = dir.path().join("project");
    std::fs::create_dir_all(&project).unwrap();
    let store = Arc::new(Store::open(&db_dir).unwrap());
    let bus = Arc::new(Bus::default());
    let browser = Arc::new(Browser::new(std::slice::from_ref(&project)).unwrap());
    let now = Utc::now();
    let session = Session {
        id: "session-1".into(),
        host_id: store.host_id().unwrap(),
        cwd: workspace::display(&project),
        title: "Test".into(),
        status: "completed".into(),
        activity: "Completed".into(),
        needs_attention: false,
        runtime_attached: false,
        attention: None,
        created_at: now,
        updated_at: now,
        session_file: String::new(),
    };
    store.save(&session).unwrap();
    let registry = Registry::new(
        store.clone(),
        bus.clone(),
        browser,
        session.host_id.clone(),
        "unused".into(),
        vec![],
        1,
    )
    .unwrap();
    let mut events = bus.subscribe();
    let db = Connection::open(db_dir.join("pinkcollab.db")).unwrap();
    db.execute_batch("BEGIN IMMEDIATE").unwrap();

    let updating = {
        let registry = registry.clone();
        tokio::spawn(async move {
            registry
                .apply("session-1", json!({"type":"agent_start"}))
                .await
        })
    };
    tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            let state = registry.state.lock().await;
            if state.entries["session-1"].session.status == "running" {
                break;
            }
            drop(state);
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(registry.list().await[0].status, "completed");
    assert_eq!(
        registry.detail("session-1").await.unwrap().session.status,
        "completed"
    );
    db.execute_batch("COMMIT").unwrap();
    updating.await.unwrap().unwrap();
    assert_eq!(
        registry.detail("session-1").await.unwrap().session.status,
        "running"
    );
    assert_eq!(events.recv().await.unwrap().kind, "session.updated");

    db.execute_batch(
        "CREATE TRIGGER fail_session_update BEFORE UPDATE ON sessions \
         BEGIN SELECT RAISE(FAIL, 'forced metadata failure'); END;",
    )
    .unwrap();
    assert!(
        registry
            .apply(
                "session-1",
                json!({"type":"extension_ui_request","method":"input","id":"question-1","title":"Question"}),
            )
            .await
            .is_err()
    );
    let detail = registry.detail("session-1").await.unwrap();
    assert_eq!(detail.session.status, "running");
    assert!(!detail.session.needs_attention);
    assert_eq!(registry.list().await[0].status, "running");
    assert_eq!(store.sessions().unwrap()[0].status, "running");
    assert!(matches!(
        events.try_recv(),
        Err(tokio::sync::broadcast::error::TryRecvError::Empty)
    ));
}
