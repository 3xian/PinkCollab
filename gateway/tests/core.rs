use pinkcollab_gateway::{
    config::Config,
    events::{Bus, normalize},
    funnel,
    model::{Session, TimelineItem},
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
fn normalized_events_keep_tasks_open_until_agent_end() {
    assert!(normalize(&json!({"type":"turn_end"})).status.is_none());
    assert_eq!(
        normalize(&json!({"type":"agent_end"})).status,
        Some("completed")
    );
    let tool = normalize(
        &json!({"type":"tool_execution_start","toolCallId":"call-1","toolName":"bash","args":{"command":"cargo test"}}),
    );
    assert_eq!(tool.activity.as_deref(), Some("Testing"));
    let trace = tool.item.unwrap().tool.unwrap();
    assert_eq!(trace.call_id, "call-1");
    assert_eq!(trace.name, "bash");
    assert_eq!(trace.arguments["command"], "cargo test");
    assert_eq!(normalize(&json!({"type":"extension_ui_request","method":"select","id":"q","title":"API?","options":["v1","v2"]})).attention.unwrap().options,["v1","v2"]);
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
