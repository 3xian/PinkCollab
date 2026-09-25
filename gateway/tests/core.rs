use pinkcollab_gateway::{
    config::Config,
    events::Bus,
    funnel,
    model::TimelineItem,
    storage::Store,
    v2_model::{OperationRecord, SessionRecord},
    workspace::Browser,
};
use rusqlite::Connection;
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
    assert_eq!(store.clients().unwrap().len(), 1);
    // An unknown id must not look like a successful revocation.
    assert!(!store.revoke("client_missing").unwrap());
    assert!(store.revoke(&client).unwrap());
    assert!(!store.authenticate(&credential));
    assert!(store.clients().unwrap().is_empty());
    drop(store);
    assert_eq!(
        Store::open(dir.path()).unwrap().host_id().unwrap(),
        identity
    );
}

#[test]
fn legacy_client_schema_migrates_without_losing_devices() {
    let dir = tempfile::tempdir().unwrap();
    let db = Connection::open(dir.path().join("pinkcollab.db")).unwrap();
    db.execute_batch(
        "CREATE TABLE clients (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            token_hash TEXT UNIQUE NOT NULL
        );
        INSERT INTO clients (id,name,token_hash)
        VALUES ('client_legacy','Older phone','legacy_hash');",
    )
    .unwrap();
    drop(db);

    let store = Store::open(dir.path()).unwrap();
    let clients = store.clients().unwrap();
    assert_eq!(clients.len(), 1);
    assert_eq!(clients[0].id, "client_legacy");
    assert_eq!(clients[0].name, "Older phone");
    assert_eq!(clients[0].created_at, None);

    let token = store.new_pairing().unwrap();
    let (new_client, _) = store.pair(&token, "New phone").unwrap();
    let clients = store.clients().unwrap();
    assert_eq!(clients.len(), 2);
    assert!(
        clients
            .iter()
            .any(|client| client.id == new_client && client.created_at.is_some())
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
fn tool_result_keeps_call_metadata_and_start_timestamp() {
    let started_at = "2026-09-20T00:00:00Z".parse().unwrap();
    let mut item = TimelineItem::tool_started(
        "call-1",
        "bash",
        json!({"command":"cargo test"}),
        started_at,
    );
    item.merge_tool_update(TimelineItem::tool_completed(
        "call-1",
        "",
        "passed",
        false,
        "2026-09-20T00:00:10Z".parse().unwrap(),
    ));

    let trace = item.tool.as_ref().unwrap();
    assert_eq!(item.timestamp, started_at);
    assert_eq!(trace.name, "bash");
    assert_eq!(trace.arguments["command"], "cargo test");
    assert_eq!(trace.result, "passed");

    let wire = serde_json::to_value(&item).unwrap();
    assert_eq!(wire["tool"]["arguments"]["command"], "cargo test");
    // The payload travels once, inside `tool`: the display fields are derived and carry nothing.
    assert_eq!(wire["detail"], "");
    assert_eq!(wire["text"], "Finished · bash");
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
#[tokio::test]
async fn oversized_resource_change_requests_resynchronization() {
    let bus = Bus::default();
    let mut client = bus.subscribe();
    bus.publish_resource(
        "session/example",
        json!([{"type":"large","value":"x".repeat(300 * 1024)}]),
    );
    let event = client.recv().await.unwrap();
    assert_eq!(event.kind, "resource_resync");
    assert_eq!(event.payload["resource"], "session/example");
    assert_eq!(bus.cursor("session/example").revision, 1);
}
#[test]
fn config_rejects_non_loopback_listeners() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(
        dir.path().join("config.yaml"),
        "listen: 0.0.0.0:8787\nworkspaces: [projects]\n",
    )
    .unwrap();
    assert!(Config::load(dir.path()).is_err());
}
#[test]
fn loopback_listener_with_https_public_url_is_valid() {
    // Tailscale Funnel/serve and HTTPS reverse proxies terminate TLS upstream.
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(
        dir.path().join("config.yaml"),
        "listen: 127.0.0.1:8787\npublic_url: https://my-host.example-tailnet.ts.net\nworkspaces: [projects]\n",
    )
    .unwrap();
    let config = Config::load(dir.path()).unwrap();
    assert!(config.listen.ip().is_loopback());
    assert_eq!(config.public_url, "https://my-host.example-tailnet.ts.net");
}
#[test]
fn config_accepts_legacy_null_tls_fields_but_does_not_write_them() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(
        dir.path().join("config.yaml"),
        "listen: 127.0.0.1:8787\nworkspaces: [projects]\ntls_cert: null\ntls_key: null\n",
    )
    .unwrap();
    let config = Config::load(dir.path()).unwrap();
    let serialized = serde_yaml::to_string(&config).unwrap();
    assert!(!serialized.contains("tls_cert"));
    assert!(!serialized.contains("tls_key"));
}
#[test]
fn config_explains_how_to_migrate_legacy_embedded_tls() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(
        dir.path().join("config.yaml"),
        "listen: 192.168.1.20:8787\nworkspaces: [projects]\ntls_cert: cert.pem\ntls_key: key.pem\n",
    )
    .unwrap();
    let error = Config::load(dir.path()).unwrap_err().to_string();
    assert!(error.contains("no longer supported"));
    assert!(error.contains("terminate TLS"));
}
#[test]
fn config_rejects_public_url_that_is_not_a_root() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(
        dir.path().join("config.yaml"),
        "listen: 127.0.0.1:8787\npublic_url: https://my-host.example-tailnet.ts.net/gateway\nworkspaces: [projects]\n",
    )
    .unwrap();
    assert!(Config::load(dir.path()).is_err());
}
#[test]
fn funnel_builds_the_forward_and_public_urls() {
    assert_eq!(funnel::target(8787), "http://127.0.0.1:8787");
    assert_eq!(
        funnel::public_url("my-host.example-tailnet.ts.net", 443),
        "https://my-host.example-tailnet.ts.net"
    );
    assert_eq!(
        funnel::public_url("my-host.example-tailnet.ts.net", 8443),
        "https://my-host.example-tailnet.ts.net:8443"
    );
    assert!(funnel::allows_https_port(443) && !funnel::allows_https_port(8787));
}
#[test]
fn funnel_rejects_ports_and_listeners_before_touching_tailscale() {
    let dir = tempfile::tempdir().unwrap();
    let options = |https_port| funnel::Options {
        https_port,
        dry_run: true,
        binary: None,
    };
    assert!(funnel::setup(dir.path(), &Config::default(), options(8787)).is_err());
    let public = Config {
        listen: "0.0.0.0:8787".parse().unwrap(),
        ..Config::default()
    };
    assert!(funnel::setup(dir.path(), &public, options(443)).is_err());
}
#[test]
fn v2_create_is_lazy_and_idempotent() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    let now = chrono::Utc::now();
    let record = SessionRecord {
        id: "sess_first".into(),
        host_id: store.host_id().unwrap(),
        cwd: "project".into(),
        title: "Task".into(),
        metadata_revision: 1,
        created_at: now,
        updated_at: now,
        archived_at: None,
        engine_session_ref: None,
    };
    let (first, replayed) = store
        .create_v2("client", "command", "fingerprint", record.clone())
        .unwrap();
    assert!(!replayed);
    assert_eq!(first.id, record.id);
    assert!(first.engine_session_ref.is_none());
    let mut another = record;
    another.id = "sess_second".into();
    let (same, replayed) = store
        .create_v2("client", "command", "fingerprint", another.clone())
        .unwrap();
    assert!(replayed);
    assert_eq!(same.id, first.id);
    assert!(
        store
            .create_v2("client", "command", "changed", another)
            .is_err()
    );
    assert_eq!(store.v2_sessions().unwrap().len(), 1);
}

#[test]
fn v2_migration_keeps_identity_authorization_and_engine_mapping() {
    let dir = tempfile::tempdir().unwrap();
    let original_id;
    let credential;
    let pairing;
    {
        let store = Store::open(dir.path()).unwrap();
        original_id = store.host_id().unwrap();
        let token = store.new_pairing().unwrap();
        (_, credential) = store.pair(&token, "phone").unwrap();
        pairing = store.new_pairing().unwrap();
    }
    // Recreate a pre-v2 schema, preserving its data, to exercise the actual migration path.
    let db = Connection::open(dir.path().join("pinkcollab.db")).unwrap();
    db.execute_batch("DROP TABLE runtime_leases; DROP TABLE operation_records; DROP TABLE create_receipts; DROP TABLE session_records; PRAGMA user_version=0;").unwrap();
    db.execute_batch("CREATE TABLE sessions (id TEXT PRIMARY KEY,metadata TEXT NOT NULL,session_file TEXT NOT NULL DEFAULT '');").unwrap();
    let now = chrono::Utc::now().to_rfc3339();
    db.execute("INSERT INTO sessions VALUES (?1,?2,?3)",rusqlite::params!["sess_legacy",serde_json::json!({"id":"sess_legacy","hostId":original_id,"cwd":"project","title":"Old task","status":"needs_input","activity":"Old activity","needsAttention":true,"createdAt":now,"updatedAt":now}).to_string(),"omp-session.jsonl"]).unwrap();
    drop(db);
    let store = Store::open(dir.path()).unwrap();
    assert_eq!(store.host_id().unwrap(), original_id);
    assert!(store.authenticate(&credential));
    assert!(store.pair(&pairing, "second phone").is_ok());
    let migrated = store.v2_session("sess_legacy").unwrap().unwrap();
    assert_eq!(
        migrated.engine_session_ref.as_deref(),
        Some("omp-session.jsonl")
    );
    assert_eq!(migrated.title, "Old task");
    let wire = serde_json::to_value(migrated).unwrap();
    assert!(wire.get("status").is_none());
    assert!(wire.get("engineSessionRef").is_none());
    assert_eq!(
        std::fs::read_dir(dir.path())
            .unwrap()
            .filter_map(Result::ok)
            .filter(|entry| entry
                .file_name()
                .to_string_lossy()
                .starts_with("pinkcollab-pre-v2-"))
            .count(),
        1
    );
}

#[test]
fn failed_v2_migration_rolls_back_and_keeps_a_consistent_backup() {
    let dir = tempfile::tempdir().unwrap();
    let db_path = dir.path().join("pinkcollab.db");
    let db = Connection::open(&db_path).unwrap();
    db.execute_batch("PRAGMA user_version=0; CREATE TABLE sessions (id TEXT PRIMARY KEY,metadata TEXT NOT NULL,session_file TEXT NOT NULL DEFAULT '');").unwrap();
    db.execute(
        "INSERT INTO sessions VALUES ('broken','{invalid json}','original.jsonl')",
        [],
    )
    .unwrap();
    drop(db);
    assert!(Store::open(dir.path()).is_err());
    let db = Connection::open(&db_path).unwrap();
    assert_eq!(
        db.query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))
            .unwrap(),
        0
    );
    assert_eq!(
        db.query_row("SELECT COUNT(*) FROM sessions", [], |row| row
            .get::<_, i64>(0))
            .unwrap(),
        1
    );
    assert_eq!(
        db.query_row(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='session_records'",
            [],
            |row| row.get::<_, i64>(0)
        )
        .unwrap(),
        0
    );
    let backup = std::fs::read_dir(dir.path())
        .unwrap()
        .filter_map(Result::ok)
        .find(|entry| {
            entry
                .file_name()
                .to_string_lossy()
                .starts_with("pinkcollab-pre-v2-")
        })
        .expect("pre-migration backup");
    let saved = Connection::open(backup.path()).unwrap();
    assert_eq!(
        saved
            .query_row(
                "SELECT session_file FROM sessions WHERE id='broken'",
                [],
                |row| row.get::<_, String>(0)
            )
            .unwrap(),
        "original.jsonl"
    );
}

#[tokio::test]
async fn corrupt_or_missing_omp_history_is_not_an_empty_conversation() {
    let dir = tempfile::tempdir().unwrap();
    let missing = dir.path().join("missing.jsonl");
    assert!(
        pinkcollab_gateway::history::history_page(&missing, "sess", None, 50)
            .await
            .is_err()
    );
    let corrupt = dir.path().join("corrupt.jsonl");
    std::fs::write(&corrupt, "{invalid json}\n").unwrap();
    assert!(
        pinkcollab_gateway::history::history_page(&corrupt, "sess", None, 50)
            .await
            .is_err()
    );
}

#[test]
fn restart_never_replays_unfinished_operations() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    let now = chrono::Utc::now();
    for (command_id, status) in [
        ("accepted", "accepted"),
        ("dispatching", "dispatching"),
        ("running", "running"),
    ] {
        let mut receipt = OperationRecord {
            command_id: command_id.into(),
            client_id: "client".into(),
            session_id: "session".into(),
            command_type: "prompt".into(),
            request_fingerprint: command_id.into(),
            runtime_generation: None,
            status: "accepted".into(),
            result: None,
            error: None,
            created_at: now,
            updated_at: now,
        };
        assert!(store.insert_operation(&receipt).unwrap());
        if status != "accepted" {
            receipt.status = status.into();
            assert!(store.update_operation(&receipt).unwrap());
        }
    }
    drop(store);
    let store = Store::open(dir.path()).unwrap();
    store.recover_operations().unwrap();
    assert_eq!(
        store
            .operation("client", "session", "accepted")
            .unwrap()
            .unwrap()
            .status,
        "cancelled"
    );
    for id in ["dispatching", "running"] {
        assert_eq!(
            store
                .operation("client", "session", id)
                .unwrap()
                .unwrap()
                .status,
            "outcome_unknown"
        );
    }
}

#[test]
fn runtime_lease_requires_the_exact_generation_to_clear() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    assert!(store.reserve_runtime("session", "run-one").unwrap());
    assert!(!store.reserve_runtime("session", "run-two").unwrap());
    assert_eq!(
        store.runtime_leases().unwrap(),
        vec![("session".into(), "run-one".into())]
    );
    assert!(!store.release_runtime("session", "run-two").unwrap());
    assert!(store.release_runtime("session", "run-one").unwrap());
    assert!(store.reserve_runtime("session", "run-two").unwrap());
}
