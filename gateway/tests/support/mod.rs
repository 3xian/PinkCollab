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
        let v2 = pinkcollab_gateway::v2_runtime::SessionDirectory::new(
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
            bus,
            v2,
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
            url,
            server,
        }
    }
    pub(crate) fn cwd(&self, name: &str) -> String {
        let path = self.dir.path().join("projects").join(name);
        std::fs::create_dir_all(&path).unwrap();
        pinkcollab_gateway::workspace::display(&path)
    }
    pub(crate) async fn pair(&self) -> String {
        let token = self.store.new_pairing().unwrap();
        let response = reqwest::Client::new()
            .post(format!("{}/api/v2/pair", self.url))
            .json(&json!({"token":token,"name":"phone"}))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 201);
        let body: Value = response.json().await.unwrap();
        assert_eq!(body["protocolVersion"], 2);
        assert_eq!(body["host"]["id"], self.host.id);
        body["credential"].as_str().unwrap().into()
    }
}
impl Drop for Harness {
    fn drop(&mut self) {
        self.server.abort();
    }
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
            if receipt["status"] == status {
                return receipt;
            }
            if ["failed", "outcome_unknown", "cancelled"]
                .iter()
                .any(|terminal| receipt["status"] == *terminal)
            {
                panic!("unexpected receipt: {receipt}");
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap()
}
