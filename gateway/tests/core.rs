use pinkcollab_gateway::{
    config::Config,
    domain::{OperationRecord, SessionRecord},
    events::{Bus, Event},
    funnel,
    model::TimelineItem,
    protocol::ServerEvent,
    storage::Store,
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
        bus.publish(ServerEvent::Timeline {
            session_id: "example".into(),
            upsert: vec![],
            remove: vec![],
            reset: false,
        });
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
    bus.publish(ServerEvent::Timeline {
        session_id: "example".into(),
        upsert: vec![],
        remove: vec!["x".repeat(9 * 1024 * 1024)],
        reset: false,
    });
    let event = client.recv().await.unwrap();
    assert!(matches!(event, Event::Disconnect));
    assert_eq!(bus.sequence(Some("example")), 1);
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
        last_activity_at: now,
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
    let wire =
        serde_json::to_value(pinkcollab_gateway::protocol::SessionDto::from(&migrated)).unwrap();
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
fn v3_migration_protects_legacy_and_aborted_prompt_history() {
    let dir = tempfile::tempdir().unwrap();
    drop(Store::open(dir.path()).unwrap());
    let db = Connection::open(dir.path().join("pinkcollab.db")).unwrap();
    db.execute_batch(
        "ALTER TABLE session_records DROP COLUMN history_may_have_been_written;
         PRAGMA user_version=2;
         INSERT INTO session_records
             (id,host_id,cwd,title,metadata_revision,created_at,updated_at,engine_session_ref)
         VALUES
             ('legacy','host','/work','Legacy',1,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','legacy.jsonl'),
             ('empty','host','/work','Empty',2,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','empty.jsonl'),
             ('aborted','host','/work','Aborted',2,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','aborted.jsonl');
         INSERT INTO operation_records
             (client_id,session_id,command_id,command_type,request_fingerprint,status,created_at,updated_at)
         VALUES
             ('phone','aborted','prompt','prompt','fingerprint','cancelled','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z');",
    )
    .unwrap();
    drop(db);

    let store = Store::open(dir.path()).unwrap();
    assert!(
        !store
            .reference_is_unwritten("legacy", "legacy.jsonl")
            .unwrap()
    );
    assert!(
        !store
            .reference_is_unwritten("aborted", "aborted.jsonl")
            .unwrap()
    );
    assert!(
        !store
            .replace_missing_engine_ref_with_feedback("aborted", 2, "aborted.jsonl", "new.jsonl")
            .unwrap()
    );
    assert!(
        store
            .reference_is_unwritten("empty", "empty.jsonl")
            .unwrap()
    );
    assert!(
        store
            .replace_missing_engine_ref_with_feedback("empty", 2, "empty.jsonl", "new.jsonl")
            .unwrap()
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

fn operation_receipt(index: i64) -> OperationRecord {
    let created_at = chrono::DateTime::parse_from_rfc3339("2026-01-01T08:00:00+08:00")
        .unwrap()
        .with_timezone(&chrono::Utc)
        + chrono::Duration::seconds(index);
    OperationRecord {
        command_id: format!("command-{index}"),
        client_id: format!("client-{}", index % 2),
        session_id: "session".into(),
        command_type: "prompt".into(),
        request_fingerprint: format!("fingerprint-{index}"),
        runtime_generation: (index % 2 == 0).then(|| "generation".into()),
        status: "succeeded".into(),
        result: (index % 2 == 0).then(|| json!({"nested":[null, true, "结果", index]})),
        error: (index % 2 != 0).then(|| json!({"code":"example","message":"错误"})),
        created_at,
        updated_at: created_at + chrono::Duration::seconds(1),
    }
}

#[test]
fn recent_operations_preserve_fields_filter_order_and_limit() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    assert!(store.recent_operations("session", 100).unwrap().is_empty());
    assert!(
        store
            .operation("client-0", "session", "missing")
            .unwrap()
            .is_none()
    );

    for index in 0..120 {
        let expected = operation_receipt(index);
        store.insert_operation(&expected).unwrap();
        if index == 0 {
            let rows = store.recent_operations("session", 100).unwrap();
            assert_eq!(rows.len(), 1);
            assert_eq!(rows[0].command_id, expected.command_id);
        }
    }
    let mut other = operation_receipt(120);
    other.session_id = "other-session".into();
    store.insert_operation(&other).unwrap();
    let db = Connection::open(dir.path().join("pinkcollab.db")).unwrap();
    db.execute(
        "UPDATE operation_records SET updated_at='2026-01-01T08:02:00+08:00' WHERE command_id='command-119'",
        [],
    )
    .unwrap();
    for limit in [0, 1, 7, 100, 101, usize::MAX] {
        let rows = store.recent_operations("session", limit).unwrap();
        assert_eq!(rows.len(), limit.min(100));
        for (offset, actual) in rows.iter().enumerate() {
            let expected = operation_receipt(119 - offset as i64);
            let single = store
                .operation(&expected.client_id, "session", &expected.command_id)
                .unwrap()
                .unwrap();
            for record in [actual, &single] {
                assert_eq!(record.client_id, expected.client_id);
                assert_eq!(record.session_id, expected.session_id);
                assert_eq!(record.command_id, expected.command_id);
                assert_eq!(record.command_type, expected.command_type);
                assert_eq!(record.request_fingerprint, expected.request_fingerprint);
                assert_eq!(record.runtime_generation, expected.runtime_generation);
                assert_eq!(record.status, expected.status);
                assert_eq!(record.result, expected.result);
                assert_eq!(record.error, expected.error);
                assert_eq!(record.created_at, expected.created_at);
                assert_eq!(record.updated_at, expected.updated_at);
            }
        }
    }
}

#[test]
fn operation_reads_reject_corrupt_fields_without_returning_partial_results() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    let db = Connection::open(dir.path().join("pinkcollab.db")).unwrap();
    let receipt = operation_receipt(0);
    // SQLite accepts these values despite TEXT affinity; both readers must still reject them.
    for assignment in [
        "result='{invalid'",
        "error='{invalid'",
        "created_at='0000-invalid-time'",
        "updated_at='not-a-time'",
        "command_type=X'FF'",
        "runtime_generation=X'FF'",
    ] {
        db.execute("DELETE FROM operation_records", []).unwrap();
        store.insert_operation(&receipt).unwrap();
        store.insert_operation(&operation_receipt(1)).unwrap();
        db.execute(
            &format!("UPDATE operation_records SET {assignment} WHERE command_id='command-0'"),
            [],
        )
        .unwrap();
        assert!(
            store
                .operation(&receipt.client_id, "session", &receipt.command_id)
                .is_err()
        );
        assert!(store.recent_operations("session", 100).is_err());
        // Invalid rows outside the selected window do not poison the read.
        assert_eq!(
            store.recent_operations("session", 1).unwrap()[0].command_id,
            "command-1"
        );
    }
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

#[test]
fn upgrading_preserves_unverified_runtime_leases() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    assert!(store.reserve_runtime("session", "before-upgrade").unwrap());
    drop(store);
    let db = Connection::open(dir.path().join("pinkcollab.db")).unwrap();
    db.execute_batch(
        "ALTER TABLE runtime_leases DROP COLUMN windows_job_name;
         PRAGMA user_version=5;",
    )
    .unwrap();
    drop(db);

    let store = Store::open(dir.path()).unwrap();
    assert_eq!(
        store.runtime_leases().unwrap(),
        vec![("session".into(), "before-upgrade".into())]
    );
    assert!(!store.reserve_runtime("session", "after-upgrade").unwrap());
    assert!(!store.release_runtime("session", "after-upgrade").unwrap());
    assert!(store.release_runtime("session", "before-upgrade").unwrap());
    assert!(store.reserve_runtime("session", "after-upgrade").unwrap());
}

#[test]
fn todo_snapshot_is_typed_and_survives_reverse_tool_updates() {
    let timestamp = "2026-09-20T00:00:00Z".parse().unwrap();
    let mut item = TimelineItem::tool_completed("todo-1", "todo", "truncated", false, timestamp)
        .with_todo_details(&json!({"phases":[{"name":"Ship","tasks":[
            {"content":"Verify (dropped)","status":"pending"},
            {"content":"Review","status":"blocked","blocker":"Waiting for access"}
        ]}]}));
    item.merge_tool_update(TimelineItem::tool_started(
        "todo-1",
        "todo",
        json!({"op":"init"}),
        timestamp,
    ));
    let wire = serde_json::to_value(item).unwrap();
    assert_eq!(
        wire["tool"]["todoPhases"][0]["tasks"][0]["content"],
        "Verify (dropped)"
    );
    assert_eq!(
        wire["tool"]["todoPhases"][0]["tasks"][0]["status"],
        "pending"
    );
    assert_eq!(
        wire["tool"]["todoPhases"][0]["tasks"][1]["blocker"],
        "Waiting for access"
    );
    assert_eq!(wire["tool"]["result"], "truncated");
    assert_eq!(wire["tool"]["arguments"]["op"], "init");
}

#[test]
fn invalid_todo_details_are_unavailable_and_clear_is_an_empty_snapshot() {
    let timestamp = "2026-09-20T00:00:00Z".parse().unwrap();
    for details in [
        json!({}),
        json!({"phases":[{"name":"Ship","tasks":[{"content":"Check","status":"future"}]}]}),
    ] {
        let item = TimelineItem::tool_completed("todo-1", "todo", "output", false, timestamp)
            .with_todo_details(&details);
        assert!(item.tool.unwrap().todo_phases.is_none());
    }
    let cleared = TimelineItem::tool_completed("todo-1", "todo", "cleared", false, timestamp)
        .with_todo_details(&json!({"phases":[]}));
    assert!(cleared.tool.unwrap().todo_phases.unwrap().is_empty());
    for (name, error) in [("read", false), ("todo", true)] {
        let item = TimelineItem::tool_completed("call", name, "output", error, timestamp)
            .with_todo_details(&json!({"phases":[]}));
        assert!(item.tool.unwrap().todo_phases.is_none());
    }
}

#[tokio::test]
async fn saved_history_preserves_structured_todo_snapshots() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("session.jsonl");
    let entry = json!({"id":"entry", "type":"message", "parentId":null,
    "timestamp":"2026-09-20T00:00:00Z", "message":{
        "role":"toolResult", "toolCallId":"todo-1", "toolName":"todo",
        "content":[{"type":"text", "text":"human-readable output"}],
        "details":{"phases":[{"name":"Ship","tasks":[{"content":"Verify (dropped)","status":"pending"}]}]}
    }});
    std::fs::write(&path, format!("{entry}\n")).unwrap();
    let items = pinkcollab_gateway::history::history(&path).await.unwrap();
    let wire = serde_json::to_value(&items[0]).unwrap();
    assert_eq!(
        wire["tool"]["todoPhases"][0]["tasks"][0]["content"],
        "Verify (dropped)"
    );
}
