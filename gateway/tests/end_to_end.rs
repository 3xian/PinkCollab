#![cfg(feature = "test-fixtures")]
use futures_util::StreamExt;
use pinkcollab_gateway::{
    api::{self, App},
    events::Bus,
    model::Host,
    session::Registry,
    storage::Store,
    workspace::Browser,
};
use serde_json::{Value, json};
use std::{sync::Arc, time::Duration};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;

struct Harness {
    dir: tempfile::TempDir,
    store: Arc<Store>,
    registry: Arc<Registry>,
    browser: Arc<Browser>,
    bus: Arc<Bus>,
    host: Host,
    url: String,
    server: tokio::task::JoinHandle<()>,
}
impl Harness {
    async fn new(max: usize) -> Self {
        Self::with_args(max, vec![]).await
    }
    async fn with_args(max: usize, args: Vec<String>) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("projects");
        std::fs::create_dir_all(&root).unwrap();
        let store = Arc::new(Store::open(&dir.path().join("data")).unwrap());
        let browser = Arc::new(Browser::new(std::slice::from_ref(&root)).unwrap());
        let bus = Arc::new(Bus::default());
        let host = Host {
            id: store.host_id().unwrap(),
            name: "test-host".into(),
            os: std::env::consts::OS.into(),
            status: "online".into(),
            omp_version: "fixture".into(),
            gateway_version: "0.1.0".into(),
        };
        let registry = Registry::new(
            store.clone(),
            bus.clone(),
            browser.clone(),
            host.id.clone(),
            env!("CARGO_BIN_EXE_omp-fixture").into(),
            args,
            max,
        )
        .unwrap();
        let app = api::router(App {
            host: host.clone(),
            store: store.clone(),
            browser: browser.clone(),
            registry: registry.clone(),
            bus: bus.clone(),
        });
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let server = tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        Self {
            dir,
            store,
            registry,
            browser,
            bus,
            host,
            url,
            server,
        }
    }
    fn cwd(&self, name: &str) -> String {
        let path = self.dir.path().join("projects").join(name);
        std::fs::create_dir_all(&path).unwrap();
        pinkcollab_gateway::workspace::display(&path)
    }
    async fn pair(&self) -> String {
        let token = self.store.new_pairing().unwrap();
        let response = reqwest::Client::new()
            .post(format!("{}/api/v1/pair", self.url))
            .json(&json!({"token":token,"name":"phone"}))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 201);
        let body: Value = response.json().await.unwrap();
        assert_eq!(body["protocolVersion"], 1);
        assert_eq!(body["host"]["id"], self.host.id);
        body["credential"].as_str().unwrap().into()
    }
    async fn wait_status(&self, id: &str, status: &str) {
        tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                let detail = self.registry.detail(id).await.unwrap();
                if detail.session.status == status {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap_or_else(|_| panic!("session did not enter {status}"));
    }
}
impl Drop for Harness {
    fn drop(&mut self) {
        self.server.abort();
    }
}

#[tokio::test]
async fn phone_to_gateway_to_omp_closed_loop() {
    let h = Harness::new(8).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    assert_eq!(
        client
            .get(format!("{}/api/v1/host", h.url))
            .send()
            .await
            .unwrap()
            .status(),
        401
    );
    assert_eq!(
        client
            .get(format!("{}/api/v1/fs/list", h.url))
            .bearer_auth(&credential)
            .query(&[("path", h.dir.path().to_string_lossy().to_string())])
            .send()
            .await
            .unwrap()
            .status(),
        403
    );
    let roots: Value = client
        .get(format!("{}/api/v1/workspaces", h.url))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(roots.as_array().unwrap().len(), 1);
    let mut request = h
        .url
        .replace("http://", "ws://")
        .add_path("/api/v1/events")
        .into_client_request()
        .unwrap();
    request.headers_mut().insert(
        "Authorization",
        format!("Bearer {credential}").parse().unwrap(),
    );
    let (mut socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
    let first = socket.next().await.unwrap().unwrap();
    let snapshot: Value = serde_json::from_str(first.to_text().unwrap()).unwrap();
    assert_eq!(snapshot["type"], "snapshot");
    assert_eq!(snapshot["payload"]["protocolVersion"], 1);
    let cwd = h.cwd("shop");
    let response = client
        .post(format!("{}/api/v1/sessions", h.url))
        .bearer_auth(&credential)
        .json(&json!({"hostId":h.host.id,"cwd":cwd,"prompt":"need input"}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 201);
    let session: Value = response.json().await.unwrap();
    let id = session["id"].as_str().unwrap();
    let command = format!("{}/api/v1/sessions/{id}", h.url);
    let current: Value = client
        .get(&command)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(current["model"]["id"], "fast");
    let cycled: Value = client
        .post(format!("{command}/model/cycle"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(cycled["model"]["id"], "smart");
    let cycled: Value = client
        .get(&command)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(cycled["model"]["id"], "smart");
    h.wait_status(id, "needs_input").await;
    let mut saw_attention = false;
    tokio::time::timeout(Duration::from_secs(5), async {
        while let Some(Ok(message)) = socket.next().await {
            if message.is_text()
                && let Ok(text) = message.to_text()
            {
                let frame: Value = serde_json::from_str(text).unwrap();
                if frame["type"] == "attention.created" {
                    saw_attention = true;
                    break;
                }
            }
        }
    })
    .await
    .unwrap();
    assert!(saw_attention);
    assert_eq!(
        client
            .post(format!("{command}/respond"))
            .bearer_auth(&credential)
            .json(&json!({"id":"stale","value":"new"}))
            .send()
            .await
            .unwrap()
            .status(),
        409
    );
    assert_eq!(
        client
            .post(format!("{command}/prompt"))
            .bearer_auth(&credential)
            .json(&json!({"message":"other"}))
            .send()
            .await
            .unwrap()
            .status(),
        409
    );
    assert_eq!(
        client
            .post(format!("{command}/respond"))
            .bearer_auth(&credential)
            .json(&json!({"id":"question-1","value":"new"}))
            .send()
            .await
            .unwrap()
            .status(),
        200
    );
    h.wait_status(id, "completed").await;
    let detail: Value = client
        .get(&command)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(
        detail["timeline"]
            .as_array()
            .unwrap()
            .iter()
            .any(|item| item["text"] == "Answer received")
    );
    assert_eq!(detail["session"]["needsAttention"], false);
    // The fixture switched models on its own before answering, so the Gateway must have re-read OMP state.
    assert_eq!(detail["model"]["id"], "fast");
    let running = h
        .registry
        .create(
            h.host.id.clone(),
            h.cwd("running"),
            "hold".into(),
            String::new(),
        )
        .await
        .unwrap();
    h.wait_status(&running.id, "running").await;
    h.registry
        .command(
            running.id.clone(),
            "prompt".into(),
            "steer".into(),
            Default::default(),
        )
        .await
        .unwrap();
    h.wait_status(&running.id, "completed").await;
    h.registry
        .command(
            running.id.clone(),
            "prompt".into(),
            "hold".into(),
            Default::default(),
        )
        .await
        .unwrap();
    h.wait_status(&running.id, "running").await;
    h.registry
        .command(
            running.id.clone(),
            "interrupt".into(),
            String::new(),
            Default::default(),
        )
        .await
        .unwrap();
    h.wait_status(&running.id, "idle").await;
    h.registry
        .command(
            running.id.clone(),
            "stop".into(),
            String::new(),
            Default::default(),
        )
        .await
        .unwrap();
    h.wait_status(&running.id, "stopped").await;
    assert!(
        !h.registry
            .detail(&running.id)
            .await
            .unwrap()
            .session
            .runtime_attached
    );
    h.registry.delete(&running.id).await.unwrap();
    assert!(h.registry.detail(&running.id).await.is_err());
    h.registry.close().await;
    socket.close(None).await.unwrap();
    let restarted = Registry::new(
        h.store.clone(),
        h.bus.clone(),
        h.browser.clone(),
        h.host.id.clone(),
        env!("CARGO_BIN_EXE_omp-fixture").into(),
        vec![],
        8,
    )
    .unwrap();
    let recent = restarted.detail(id).await.unwrap();
    assert!(!recent.session.runtime_attached);
    assert!(
        recent
            .timeline
            .iter()
            .any(|item| item.text == "Answer received")
    );
    assert!(
        restarted
            .command(
                id.into(),
                "prompt".into(),
                "again".into(),
                Default::default()
            )
            .await
            .is_err()
    );
}
#[tokio::test]
async fn cycling_without_an_alternative_model_is_rejected() {
    let h = Harness::with_args(1, vec!["--single-model".into()]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let response = client
        .post(format!("{}/api/v1/sessions", h.url))
        .bearer_auth(&credential)
        .json(&json!({"hostId":h.host.id,"cwd":h.cwd("single"),"prompt":"hold"}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 201);
    let session: Value = response.json().await.unwrap();
    let response = client
        .post(format!(
            "{}/api/v1/sessions/{}/model/cycle",
            h.url,
            session["id"].as_str().unwrap()
        ))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 409);
    let body: Value = response.json().await.unwrap();
    assert_eq!(body["error"], "no alternative model is configured");
    h.registry.close().await;
}

trait AddPath {
    fn add_path(self, path: &str) -> String;
}
impl AddPath for String {
    fn add_path(self, path: &str) -> String {
        format!("{self}{path}")
    }
}

#[tokio::test]
async fn failed_prompt_and_runtime_limits_do_not_leak_processes() {
    let h = Harness::new(1).await;
    let result = h
        .registry
        .create(
            h.host.id.clone(),
            h.cwd("failed"),
            "fail".into(),
            String::new(),
        )
        .await;
    assert!(result.is_err());
    let list = h.registry.list().await;
    assert_eq!(list.len(), 1);
    assert_eq!(list[0].status, "failed");
    let s = h
        .registry
        .create(
            h.host.id.clone(),
            h.cwd("active"),
            "hold".into(),
            String::new(),
        )
        .await
        .unwrap();
    assert!(
        h.registry
            .create(
                h.host.id.clone(),
                h.cwd("limit"),
                "hold".into(),
                String::new()
            )
            .await
            .is_err()
    );
    h.registry
        .command(s.id, "stop".into(), String::new(), Default::default())
        .await
        .unwrap();
    h.registry.close().await;
}

#[tokio::test]
async fn disconnected_client_does_not_abandon_runtime() {
    let h = Harness::new(2).await;
    let registry = h.registry.clone();
    let host = h.host.id.clone();
    let cwd = h.cwd("cancelled");
    let request = tokio::spawn(async move {
        registry
            .create(host, cwd, "hold".into(), String::new())
            .await
    });
    tokio::time::sleep(Duration::from_millis(5)).await;
    request.abort();
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let list = h.registry.list().await;
            if list.first().is_some_and(|s| s.status == "running") {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    h.registry.close().await;
}

#[tokio::test]
async fn history_follows_parent_branch() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("session.jsonl");
    let message = |id: &str, parent: Option<&str>, text: &str| {
        json!({"id":id,"parentId":parent,"type":"message","timestamp":"2026-09-14T00:00:00Z","message":{"role":"assistant","content":[{"type":"text","text":text}]}}).to_string()
    };
    std::fs::write(
        &path,
        [
            message("a", None, "root"),
            message("b", Some("a"), "abandoned branch"),
            message("c", Some("a"), "current branch"),
        ]
        .join("\n"),
    )
    .unwrap();
    let history = pinkcollab_gateway::session::history(&path).await.unwrap();
    assert_eq!(
        history
            .iter()
            .map(|item| item.text.as_str())
            .collect::<Vec<_>>(),
        ["root", "current branch"]
    );
}
