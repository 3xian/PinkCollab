#![cfg(all(windows, feature = "test-fixtures"))]
//! Exercise desktop lifecycle without registering startup or touching any real Windows service.
use pinkcollab_gateway::config::Config;
use std::{
    path::PathBuf,
    process::Command,
    time::{Duration, Instant},
};

struct Desktop {
    root: tempfile::TempDir,
    data: PathBuf,
    executable: PathBuf,
    address: std::net::SocketAddr,
}
impl Desktop {
    fn new() -> Self {
        let root = tempfile::tempdir().unwrap();
        let data = root.path().join("用户's data");
        let base = root.path().join("local/PinkCollab/bin");
        std::fs::create_dir_all(&base).unwrap();
        std::fs::create_dir_all(&data).unwrap();
        let executable = base.join("pinkcollab-gateway.exe");
        std::fs::copy(env!("CARGO_BIN_EXE_gateway-fixture"), &executable).unwrap();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let config = Config {
            listen: address,
            workspaces: vec![root.path().to_owned()],
            omp: env!("CARGO_BIN_EXE_omp-fixture").into(),
            ..Config::default()
        };
        std::fs::write(
            data.join("config.yaml"),
            serde_yaml::to_string(&config).unwrap(),
        )
        .unwrap();
        std::fs::write(
            base.join("data-dir"),
            data.canonicalize().unwrap().to_string_lossy().as_bytes(),
        )
        .unwrap();
        let startup = root
            .path()
            .join("roaming/Microsoft/Windows/Start Menu/Programs/Startup");
        std::fs::create_dir_all(&startup).unwrap();
        // Installed definition uses the same quoting and encoding as the real installer.
        let command = format!(
            "\"{}\" --data-dir \"{}\" background-start",
            executable.display(),
            data.display()
        );
        let script = format!(
            "CreateObject(\"WScript.Shell\").Run \"{}\", 0, False\r\n",
            command.replace('"', "\"\"")
        );
        let bytes: Vec<u8> = std::iter::once(0xfeffu16)
            .chain(script.encode_utf16())
            .flat_map(u16::to_le_bytes)
            .collect();
        std::fs::write(startup.join("PinkCollab.vbs"), bytes).unwrap();
        let environment = serde_json::json!({"PATH":std::env::var("PATH").unwrap(), "LOCALAPPDATA":root.path().join("local"), "APPDATA":root.path().join("roaming")});
        std::fs::write(
            data.join("service-environment.json"),
            serde_json::to_vec(&environment).unwrap(),
        )
        .unwrap();
        Self {
            root,
            data,
            executable,
            address,
        }
    }
    fn command(&self) -> Command {
        let mut command = Command::new(&self.executable);
        command
            .current_dir(self.root.path())
            .arg("--data-dir")
            .arg(&self.data)
            .env("LOCALAPPDATA", self.root.path().join("local"))
            .env("APPDATA", self.root.path().join("roaming"));
        command
    }
    fn service(&self, action: &str) -> std::process::Output {
        self.command().args(["service", action]).output().unwrap()
    }
    async fn healthy(&self) -> bool {
        reqwest::get(format!("http://{}/health", self.address))
            .await
            .ok()
            .is_some_and(|response| response.status().is_success())
    }
    async fn ready(&self) {
        let deadline = Instant::now() + Duration::from_secs(15);
        while !self.healthy().await {
            assert!(
                Instant::now() < deadline,
                "Gateway log: {}",
                std::fs::read_to_string(self.data.join("gateway.log")).unwrap_or_default()
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }
}
impl Drop for Desktop {
    fn drop(&mut self) {
        let _ = self.service("stop");
    }
}
#[tokio::test]
async fn login_launcher_starts_once_and_graceful_stop_preserves_data() {
    let desktop = Desktop::new();
    let definition = desktop
        .root
        .path()
        .join("roaming/Microsoft/Windows/Start Menu/Programs/Startup/PinkCollab.vbs");
    let mut launcher = Command::new("cscript.exe");
    let output = launcher
        .current_dir(desktop.root.path())
        .arg("//nologo")
        .arg(&definition)
        .env("LOCALAPPDATA", desktop.root.path().join("local"))
        .env("APPDATA", desktop.root.path().join("roaming"))
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    desktop.ready().await;
    let process = std::fs::read(desktop.data.join("gateway-process.json")).unwrap();
    assert!(desktop.service("start").status.success());
    assert!(
        desktop
            .command()
            .arg("background-run")
            .output()
            .unwrap()
            .status
            .success()
    );
    assert_eq!(
        process,
        std::fs::read(desktop.data.join("gateway-process.json")).unwrap()
    );
    assert!(desktop.service("status").status.success());
    let config = std::fs::read(desktop.data.join("config.yaml")).unwrap();
    let host = pinkcollab_gateway::storage::Store::open(&desktop.data)
        .unwrap()
        .host_id()
        .unwrap();
    assert!(desktop.service("stop").status.success());
    assert!(!desktop.healthy().await);
    assert!(!desktop.data.join("gateway-process.json").exists());
    assert_eq!(
        config,
        std::fs::read(desktop.data.join("config.yaml")).unwrap()
    );
    assert!(desktop.service("start").status.success());
    assert!(
        desktop.healthy().await,
        "start returned before HTTP readiness"
    );
    desktop.ready().await;
    assert_eq!(
        host,
        pinkcollab_gateway::storage::Store::open(&desktop.data)
            .unwrap()
            .host_id()
            .unwrap()
    );
    let restarted = desktop.service("restart");
    assert!(
        restarted.status.success(),
        "{}",
        String::from_utf8_lossy(&restarted.stderr)
    );
    assert!(
        desktop.healthy().await,
        "restart returned before HTTP readiness"
    );
    assert_ne!(
        process,
        std::fs::read(desktop.data.join("gateway-process.json")).unwrap()
    );
}

#[tokio::test]
async fn occupied_listener_is_a_start_failure_and_can_be_retried() {
    let desktop = Desktop::new();
    let listener = std::net::TcpListener::bind(desktop.address).unwrap();
    let slow_omp = desktop.root.path().join("slow-omp.cmd");
    std::fs::write(
        &slow_omp,
        "@powershell.exe -NoProfile -Command \"Start-Sleep -Seconds 2\"\r\n",
    )
    .unwrap();
    let mut config = Config::load(&desktop.data).unwrap();
    config.omp = slow_omp.to_string_lossy().into_owned();
    std::fs::write(
        desktop.data.join("config.yaml"),
        serde_yaml::to_string(&config).unwrap(),
    )
    .unwrap();

    let failed = desktop.service("start");
    assert!(
        !failed.status.success(),
        "start incorrectly succeeded with an occupied listener"
    );
    assert!(!desktop.data.join("gateway-process.json").exists());
    assert!(String::from_utf8_lossy(&failed.stderr).contains("Background Gateway exited"));
    drop(listener);

    let started = desktop.service("start");
    assert!(
        started.status.success(),
        "{}",
        String::from_utf8_lossy(&started.stderr)
    );
    assert!(
        desktop.healthy().await,
        "start returned before HTTP readiness"
    );
    assert!(desktop.service("stop").status.success());
}

#[tokio::test]
async fn external_setup_runs_desktop_gateway_and_preserves_saved_pairing_url() {
    let desktop = Desktop::new();
    let tools = desktop.root.path().join("tools");
    std::fs::create_dir(&tools).unwrap();
    let omp = tools.join("omp.cmd");
    std::fs::write(&omp, "@echo 0.1.0\r\n").unwrap();
    let mut expected = Config::load(&desktop.data).unwrap();
    expected.omp = omp.to_string_lossy().into_owned();
    expected.name = "External desktop gateway".into();
    expected.max_sessions = 19;
    expected.public_url = "https://old-host.ts.net".into();
    std::fs::write(
        desktop.data.join("config.yaml"),
        serde_yaml::to_string(&expected).unwrap(),
    )
    .unwrap();
    std::fs::write(desktop.data.join("funnel-url"), &expected.public_url).unwrap();
    // Retain only Windows system tools and the isolated OMP fixture in PATH.
    let system = PathBuf::from(std::env::var_os("SystemRoot").unwrap()).join("System32");
    let path = std::env::join_paths([tools, system]).unwrap();
    let command = || {
        let mut command = desktop.command();
        command
            .env("HOME", desktop.root.path())
            .env("USERPROFILE", desktop.root.path())
            .env("PATH", &path);
        command
    };
    let store = pinkcollab_gateway::storage::Store::open(&desktop.data).unwrap();
    for url in ["https://proxy.example:8443", "https://replacement.example"] {
        let output = command()
            .args([
                "setup",
                "--non-interactive",
                "--transport",
                "external",
                "--public-url",
                url,
                "--workspace",
            ])
            .arg(desktop.root.path())
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{} {}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        assert!(
            desktop.healthy().await,
            "setup returned before Gateway readiness"
        );
        let saved = Config::load(&desktop.data).unwrap();
        assert_eq!(saved.public_url, url);
        assert_eq!(saved.listen, expected.listen);
        assert_eq!(saved.name, expected.name);
        assert_eq!(saved.omp, expected.omp);
        assert_eq!(saved.omp_args, expected.omp_args);
        assert_eq!(saved.max_sessions, expected.max_sessions);
        assert_eq!(
            pinkcollab_gateway::workspace::canonical_roots(&saved.workspaces).unwrap(),
            pinkcollab_gateway::workspace::canonical_roots(&expected.workspaces).unwrap()
        );
        assert!(!desktop.data.join("funnel-url").exists());
        let db = rusqlite::Connection::open(desktop.data.join("pinkcollab.db")).unwrap();
        let tokens: i64 = db
            .query_row("SELECT COUNT(*) FROM pairing", [], |row| row.get(0))
            .unwrap();
        assert_eq!(tokens, 0, "noninteractive setup created a pairing token");
        assert!(store.clients().unwrap().is_empty());
    }
    for action in ["status", "doctor"] {
        let output = command().arg(action).output().unwrap();
        assert!(
            output.status.success(),
            "{action}: {} {}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        assert!(String::from_utf8_lossy(&output.stdout).contains("Externally managed"));
    }
    let output = command().arg("pair").output().unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let payload: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(payload["version"], 1);
    assert_eq!(payload["url"], "https://replacement.example");
    store
        .pair(payload["token"].as_str().unwrap(), "External Windows phone")
        .unwrap();
}
