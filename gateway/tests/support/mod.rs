use pinkcollab_gateway::{
    api::{self, App},
    events::Bus,
    model::Host,
    storage::Store,
    workspace::Browser,
};
use serde_json::{Value, json};
use std::{sync::Arc, time::Duration};

pub(crate) struct Harness {
    pub(crate) dir: tempfile::TempDir,
    pub(crate) store: Arc<Store>,
    pub(crate) host: Host,
    #[allow(dead_code)]
    pub(crate) bus: Arc<Bus>,
    pub(crate) url: String,
    server: tokio::task::JoinHandle<()>,
}
impl Harness {
    pub(crate) async fn new(max: usize, args: Vec<String>) -> Self {
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
        let sessions = pinkcollab_gateway::runtime::SessionDirectory::new(
            store.clone(),
            browser.clone(),
            bus.clone(),
            env!("CARGO_BIN_EXE_omp-fixture").into(),
            args,
            max,
        );
        let app = api::router(App {
            host: host.clone(),
            store: store.clone(),
            browser,
            bus: bus.clone(),
            sessions,
        });
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let server = tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        Self {
            dir,
            store,
            host,
            bus,
            url,
            server,
        }
    }
    #[allow(dead_code)]
    pub(crate) async fn restart(&mut self, max: usize, args: Vec<String>) {
        self.server.abort();
        let _ = (&mut self.server).await;
        self.store = Arc::new(Store::open(&self.dir.path().join("data")).unwrap());
        self.store.recover_operations().unwrap();
        let browser = Arc::new(Browser::new(&[self.dir.path().join("projects")]).unwrap());
        self.bus = Arc::new(Bus::default());
        let sessions = pinkcollab_gateway::runtime::SessionDirectory::new(
            self.store.clone(),
            browser.clone(),
            self.bus.clone(),
            env!("CARGO_BIN_EXE_omp-fixture").into(),
            args,
            max,
        );
        let app = api::router(App {
            host: self.host.clone(),
            store: self.store.clone(),
            browser,
            bus: self.bus.clone(),
            sessions,
        });
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        self.url = format!("http://{}", listener.local_addr().unwrap());
        self.server = tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
    }
    pub(crate) fn cwd(&self, name: &str) -> String {
        let path = self.dir.path().join("projects").join(name);
        std::fs::create_dir_all(&path).unwrap();
        pinkcollab_gateway::workspace::display(&path)
    }
    pub(crate) async fn pair(&self) -> String {
        let token = self.store.new_pairing().unwrap();
        let response = reqwest::Client::new()
            .post(format!("{}/api/v4/pair", self.url))
            .json(&json!({"token":token,"name":"phone"}))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 201);
        let body: Value = response.json().await.unwrap();
        assert_eq!(body["protocolVersion"], 4);
        assert_eq!(body["host"]["id"], self.host.id);
        body["credential"].as_str().unwrap().into()
    }
}
impl Drop for Harness {
    fn drop(&mut self) {
        self.server.abort();
    }
}

#[allow(dead_code)]
pub(crate) async fn create_session(
    client: &reqwest::Client,
    harness: &Harness,
    credential: &str,
    cwd: &str,
) -> String {
    let sessions = format!("{}/api/v4/sessions", harness.url);
    let response = client
        .post(&sessions)
        .bearer_auth(credential)
        .json(&json!({"commandId":"create","hostId":harness.host.id,"cwd":cwd}))
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap();
    let record: Value = response.json().await.unwrap();
    format!("{sessions}/{}", record["id"].as_str().unwrap())
}

#[allow(dead_code)]
pub(crate) async fn command_succeeds(
    client: &reqwest::Client,
    session: &str,
    credential: &str,
    command_id: &str,
    mut command: Value,
) {
    command["commandId"] = json!(command_id);
    assert_eq!(
        client
            .post(format!("{session}/commands"))
            .bearer_auth(credential)
            .json(&command)
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    wait_operation(
        client,
        &format!("{session}/operations/{command_id}"),
        credential,
        "succeeded",
    )
    .await;
}

pub(crate) async fn wait_operation(
    client: &reqwest::Client,
    url: &str,
    credential: &str,
    status: &str,
) -> Value {
    tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            let receipt: Value = client
                .get(url)
                .bearer_auth(credential)
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            if receipt["state"] == status {
                return receipt;
            }
            if ["failed", "unknown", "cancelled"]
                .iter()
                .any(|terminal| receipt["state"] == *terminal)
            {
                panic!("unexpected receipt: {receipt}");
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap()
}

#[allow(dead_code)]
pub(crate) async fn runtime_generation(
    client: &reqwest::Client,
    session: &str,
    credential: &str,
) -> String {
    let value: Value = client
        .get(session)
        .bearer_auth(credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    value["runtime"]["generation"].as_str().unwrap().to_owned()
}

#[allow(dead_code)]
pub(crate) async fn wait_runtime(
    client: &reqwest::Client,
    session: &str,
    credential: &str,
    state: &str,
) -> Value {
    tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            let value: Value = client
                .get(session)
                .bearer_auth(credential)
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            if value["runtime"]["state"] == state {
                return value["runtime"].clone();
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap()
}
