use pinkcollab_gateway::{
    config::Config,
    events::{Bus, normalize},
    model::Session,
    storage::Store,
    workspace::Browser,
};
use serde_json::json;
use std::sync::Arc;

#[test]
fn pairing_is_single_use_and_revocable() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    let identity = store.host_id().unwrap();
    let token = store.new_pairing().unwrap();
    assert!(!store.authenticate(&token));
    assert!(store.pair("invalid", "phone").is_err());
    let (client, credential) = store.pair(&token, "phone").unwrap();
    assert!(store.authenticate(&credential));
    assert!(store.pair(&token, "replay").is_err());
    store.revoke(&client).unwrap();
    assert!(!store.authenticate(&credential));
    drop(store);
    assert_eq!(
        Store::open(dir.path()).unwrap().host_id().unwrap(),
        identity
    );
}
#[test]
fn concurrent_pairing_only_one_client_wins() {
    let dir = tempfile::tempdir().unwrap();
    let store = Arc::new(Store::open(dir.path()).unwrap());
    let token = store.new_pairing().unwrap();
    let workers = (0..8)
        .map(|_| {
            let store = store.clone();
            let token = token.clone();
            std::thread::spawn(move || store.pair(&token, "phone").is_ok())
        })
        .collect::<Vec<_>>();
    assert_eq!(
        workers
            .into_iter()
            .filter_map(|worker| worker.join().ok())
            .filter(|v| *v)
            .count(),
        1
    );
}
#[test]
fn expired_pairing_is_rejected() {
    use sha2::{Digest, Sha256};
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    let token = store.new_pairing().unwrap();
    let db = rusqlite::Connection::open(dir.path().join("pinkcollab.db")).unwrap();
    db.execute(
        "UPDATE pairing SET expires_at=0 WHERE token_hash=?",
        [hex::encode(Sha256::digest(token.as_bytes()))],
    )
    .unwrap();
    assert!(store.pair(&token, "phone").is_err());
}
#[tokio::test]
async fn workspace_prevents_traversal_and_hides_dot_directories() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("projects");
    std::fs::create_dir_all(root.join("shop")).unwrap();
    std::fs::create_dir(root.join(".ssh")).unwrap();
    std::fs::write(root.join("README"), "file").unwrap();
    let browser = Browser::new(std::slice::from_ref(&root)).unwrap();
    assert!(browser.validate(&root.join("..")).is_err());
    assert!(browser.validate(std::path::Path::new("shop")).is_err());
    assert!(browser.validate(&root.join("README")).is_err());
    let listing = browser.list(&root).await.unwrap();
    assert!(listing.parent.is_none());
    assert_eq!(
        listing
            .directories
            .iter()
            .map(|d| d.name.as_str())
            .collect::<Vec<_>>(),
        ["shop"]
    );
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(dir.path(), root.join("escape")).unwrap();
        assert!(browser.validate(&root.join("escape")).is_err());
    }
    #[cfg(windows)]
    {
        let junction = root.join("escape");
        let output = std::process::Command::new("cmd")
            .args(["/c", "mklink", "/J"])
            .arg(&junction)
            .arg(dir.path())
            .output()
            .unwrap();
        assert!(output.status.success());
        assert!(browser.validate(&junction).is_err());
        std::fs::remove_dir(&junction).unwrap();
    }
}
#[test]
fn normalized_events_keep_tasks_open_until_agent_end() {
    assert!(normalize(&json!({"type":"turn_end"})).status.is_none());
    assert_eq!(
        normalize(&json!({"type":"agent_end"})).status,
        Some("completed")
    );
    assert_eq!(normalize(&json!({"type":"tool_execution_start","toolName":"bash","args":{"command":"cargo test"}})).activity.as_deref(),Some("Testing"));
    assert_eq!(normalize(&json!({"type":"extension_ui_request","method":"select","id":"q","title":"API?","options":["v1","v2"]})).attention.unwrap().options,["v1","v2"]);
}
#[tokio::test]
async fn slow_event_clients_must_resynchronize() {
    let bus = Bus::default();
    let mut client = bus.subscribe();
    for _ in 0..300 {
        bus.publish("test", json!({}));
    }
    assert!(matches!(
        client.recv().await,
        Err(tokio::sync::broadcast::error::RecvError::Lagged(_))
    ));
}
#[test]
fn config_rejects_non_tls_public_listeners() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(
        dir.path().join("config.yaml"),
        "listen: 0.0.0.0:8787\nworkspaces: [projects]\n",
    )
    .unwrap();
    assert!(Config::load(dir.path()).is_err());
}
#[test]
fn sqlite_stores_metadata_without_transcript() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    let now = chrono::Utc::now();
    let session = Session {
        id: "s".into(),
        host_id: store.host_id().unwrap(),
        cwd: "project".into(),
        title: "task".into(),
        status: "completed".into(),
        activity: "Completed".into(),
        needs_attention: false,
        runtime_attached: false,
        attention: None,
        created_at: now,
        updated_at: now,
        session_file: "omp-session.jsonl".into(),
    };
    store.save(&session).unwrap();
    let saved = store.sessions().unwrap();
    assert_eq!(saved[0].session_file, session.session_file);
    let wire = serde_json::to_value(&saved[0]).unwrap();
    assert!(wire.get("sessionFile").is_none());
    assert!(wire.get("timeline").is_none());
}
