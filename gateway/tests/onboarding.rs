//! CLI regressions use temporary homes and fake tools, never the user's service manager.
#![cfg(unix)]
use pinkcollab_gateway::{config::Config, storage::Store};
use std::{
    io::{Read, Write},
    os::unix::fs::PermissionsExt,
    path::{Path, PathBuf},
    process::Command,
};
fn executable(path: &Path, script: &str) {
    std::fs::write(path, format!("#!/bin/sh\n{script}\n")).unwrap();
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700)).unwrap();
}
fn cli(home: &Path, data: &Path) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_pinkcollab-gateway"));
    command
        .env("HOME", home)
        .env("PATH", home.join("bin"))
        .arg("--data-dir")
        .arg(data);
    command
}
fn tools(home: &Path) {
    std::fs::create_dir(home.join("bin")).unwrap();
    executable(&home.join("bin/omp"), "echo 0.1.0");
    executable(
        &home.join("bin/tailscale"),
        "echo '{\"BackendState\":\"Stopped\"}'",
    );
}
fn versioned_omp(home: &Path) -> PathBuf {
    let entry = home.join("bin/omp");
    let versioned = home.join("bin/omp-0.1.0");
    std::fs::rename(&entry, &versioned).unwrap();
    std::os::unix::fs::symlink(&versioned, &entry).unwrap();
    entry
}
#[test]
fn doctor_reports_all_sections_even_when_config_and_tools_fail() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    std::fs::remove_file(home.path().join("bin/omp")).unwrap();
    let output = cli(home.path(), &home.path().join("missing"))
        .arg("doctor")
        .output()
        .unwrap();
    assert!(!output.status.success());
    let text = String::from_utf8(output.stdout).unwrap();
    for section in [
        "Config\n",
        "OMP\n",
        "Tailscale\n",
        "Funnel\n",
        "Service\n",
        "Gateway\n",
        "Database\n",
    ] {
        assert!(text.contains(section), "{text}");
    }
    assert!(text.contains("omp (unavailable)"));
    assert!(text.contains("backend: Stopped"));
}
#[test]
fn status_accepts_a_running_gateway_and_reports_stopped_after_shutdown() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    let data = home.path().join("data");
    Store::open(&data).unwrap();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let config = Config {
        listen: address,
        public_url: "https://host.ts.net".into(),
        omp: home.path().join("bin/omp").to_string_lossy().into(),
        workspaces: vec![home.path().into()],
        ..Config::default()
    };
    std::fs::write(
        data.join("config.yaml"),
        serde_yaml::to_string(&config).unwrap(),
    )
    .unwrap();
    executable(
        &home.path().join("bin/tailscale"),
        &format!(
            r#"if [ "$1" = status ]; then
 echo '{{"BackendState":"Running","Self":{{"DNSName":"host.ts.net."}}}}'
else
 echo '{{"TCP":{{"443":{{"HTTPS":true}}}},"Web":{{"host.ts.net:443":{{"Handlers":{{"/":{{"Proxy":"http://127.0.0.1:{}"}}}}}}}},"AllowFunnel":{{"host.ts.net:443":true}}}}'
fi"#,
            address.port()
        ),
    );
    std::fs::write(data.join("funnel-url"), &config.public_url).unwrap();
    let server = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        let mut request = [0; 1024];
        assert!(socket.read(&mut request).unwrap() > 0);
        socket
            .write_all(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\npinkcollab:ok")
            .unwrap();
    });
    let output = cli(home.path(), &data).output().unwrap();
    assert!(
        !output.status.success(),
        "{} {}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(String::from_utf8_lossy(&output.stdout).contains("Gateway       Running"));
    server.join().unwrap();
    let output = cli(home.path(), &data).output().unwrap();
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stdout).contains("Stopped"));
}
#[test]
fn noninteractive_setup_rejects_implicit_workspace_without_writing_config() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    let data = home.path().join("data");
    let output = cli(home.path(), &data)
        .args(["setup", "--non-interactive"])
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(!data.exists());
}
#[test]
fn init_persists_the_absolute_omp_symlink() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    let entry = versioned_omp(home.path());
    let data = home.path().join("data");
    let output = cli(home.path(), &data)
        .args(["init", "--workspace"])
        .arg(home.path())
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(Config::load(&data).unwrap().omp, entry.to_str().unwrap());
}

#[test]
fn setup_persists_the_absolute_omp_symlink_across_runs() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    let entry = versioned_omp(home.path());
    let data = home.path().join("data");
    std::fs::create_dir(&data).unwrap();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let config = Config {
        listen: listener.local_addr().unwrap(),
        workspaces: vec![home.path().into()],
        ..Config::default()
    };
    drop(listener);
    std::fs::write(
        data.join("config.yaml"),
        serde_yaml::to_string(&config).unwrap(),
    )
    .unwrap();
    let added = tempfile::tempdir().unwrap();
    let output = cli(home.path(), &data)
        .args(["setup", "--non-interactive", "--workspace"])
        .arg(added.path())
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert_eq!(Config::load(&data).unwrap().omp, entry.to_str().unwrap());
    assert!(
        Config::load(&data)
            .unwrap()
            .workspaces
            .contains(&added.path().canonicalize().unwrap())
    );
    let another = tempfile::tempdir().unwrap();
    let output = cli(home.path(), &data)
        .args(["setup", "--non-interactive", "--workspace"])
        .arg(another.path())
        .output()
        .unwrap();
    assert!(!output.status.success());
    let saved = Config::load(&data).unwrap();
    assert_eq!(saved.omp, entry.to_str().unwrap());
    assert!(
        saved
            .workspaces
            .contains(&another.path().canonicalize().unwrap())
    );
}

#[test]
fn setup_preserves_an_explicit_omp_reference() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    let data = home.path().join("data");
    std::fs::create_dir(&data).unwrap();
    let pinned = home.path().join("bin/pinned-omp");
    std::os::unix::fs::symlink(home.path().join("bin/omp"), &pinned).unwrap();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let config = Config {
        listen: listener.local_addr().unwrap(),
        omp: pinned.to_string_lossy().into_owned(),
        workspaces: vec![home.path().into()],
        ..Config::default()
    };
    drop(listener);
    std::fs::write(
        data.join("config.yaml"),
        serde_yaml::to_string(&config).unwrap(),
    )
    .unwrap();
    let added = tempfile::tempdir().unwrap();
    let output = cli(home.path(), &data)
        .args(["setup", "--non-interactive", "--workspace"])
        .arg(added.path())
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert_eq!(Config::load(&data).unwrap().omp, config.omp);
    assert!(
        Config::load(&data)
            .unwrap()
            .workspaces
            .contains(&added.path().canonicalize().unwrap())
    );
}

#[test]
fn pair_command_keeps_its_machine_readable_payload() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    let data = home.path().join("data");
    let store = Store::open(&data).unwrap();
    let config = Config {
        public_url: "https://host.ts.net".into(),
        workspaces: vec![home.path().into()],
        ..Config::default()
    };
    std::fs::write(
        data.join("config.yaml"),
        serde_yaml::to_string(&config).unwrap(),
    )
    .unwrap();
    let output = cli(home.path(), &data).arg("pair").output().unwrap();
    assert!(output.status.success());
    let payload: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(payload["version"], 1);
    assert_eq!(payload["url"], "https://host.ts.net");
    store
        .pair(payload["token"].as_str().unwrap(), "Phone")
        .unwrap();
}

#[test]
fn setup_conflict_has_no_configuration_or_transport_side_effects() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    let data = home.path().join("new-data");
    #[cfg(target_os = "macos")]
    let base = home.path().join("Library/Application Support/PinkCollab");
    #[cfg(target_os = "linux")]
    let base = home.path().join(".local/share/pinkcollab");
    std::fs::create_dir_all(base.join("bin")).unwrap();
    std::fs::write(base.join("bin/data-dir"), "/existing-owner").unwrap();
    executable(
        &home.path().join("bin/tailscale"),
        r#"echo invoked > "$HOME/transport-called""#,
    );
    let output = cli(home.path(), &data)
        .args(["setup", "--non-interactive", "--workspace"])
        .arg(home.path())
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stderr).contains("another data directory"));
    assert!(!data.exists());
    assert!(!home.path().join("transport-called").exists());
}

#[test]
fn setup_occupied_port_does_not_change_existing_config() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    let data = home.path().join("data");
    std::fs::create_dir(&data).unwrap();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let config = Config {
        listen: listener.local_addr().unwrap(),
        workspaces: vec![home.path().into()],
        ..Config::default()
    };
    let original = serde_yaml::to_string(&config).unwrap();
    std::fs::write(data.join("config.yaml"), &original).unwrap();
    executable(
        &home.path().join("bin/tailscale"),
        r#"echo invoked > "$HOME/transport-called""#,
    );
    let output = cli(home.path(), &data)
        .args(["setup", "--non-interactive", "--workspace"])
        .arg(home.path())
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stderr).contains("already in use"));
    assert_eq!(
        std::fs::read_to_string(data.join("config.yaml")).unwrap(),
        original
    );
    assert!(!data.join("pinkcollab.db").exists());
    assert!(!home.path().join("transport-called").exists());
}

#[test]
fn status_accepts_manual_remote_access_without_tailscale() {
    for public_url in [
        "",
        "https://proxy.example.com",
        "https://host.tailnet.ts.net",
    ] {
        let home = tempfile::tempdir().unwrap();
        tools(home.path());
        executable(&home.path().join("bin/tailscale"), "exit 99");
        let data = home.path().join("data");
        Store::open(&data).unwrap();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let config = Config {
            listen: listener.local_addr().unwrap(),
            public_url: public_url.into(),
            omp: home.path().join("bin/omp").to_string_lossy().into(),
            workspaces: vec![home.path().into()],
            ..Config::default()
        };
        std::fs::write(
            data.join("config.yaml"),
            serde_yaml::to_string(&config).unwrap(),
        )
        .unwrap();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            let mut request = [0; 1024];
            assert!(socket.read(&mut request).unwrap() > 0);
            socket
                .write_all(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\npinkcollab:ok")
                .unwrap();
        });
        let output = cli(home.path(), &data).arg("status").output().unwrap();
        server.join().unwrap();
        assert!(
            !output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        let text = String::from_utf8_lossy(&output.stdout);
        assert!(text.contains(if public_url.is_empty() {
            "Local only"
        } else {
            "Externally managed"
        }));
    }
}

#[test]
fn bare_fresh_is_read_only_and_version_is_branded() {
    let home = tempfile::tempdir().unwrap();
    let data = home.path().join("absent");
    let output = cli(home.path(), &data).output().unwrap();
    assert!(output.status.success());
    assert!(String::from_utf8_lossy(&output.stdout).contains("pinkcollab setup"));
    assert!(!data.exists());
    assert_eq!(std::fs::read_dir(home.path()).unwrap().count(), 0);
    let version = cli(home.path(), &data).arg("--version").output().unwrap();
    assert_eq!(
        String::from_utf8_lossy(&version.stdout).trim(),
        format!("pinkcollab {}", env!("CARGO_PKG_VERSION"))
    );
}

#[test]
fn bare_and_status_share_healthy_output() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    let data = home.path().join("data");
    Store::open(&data).unwrap();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let config = Config {
        listen: listener.local_addr().unwrap(),
        public_url: "https://proxy.example".into(),
        omp: home.path().join("bin/omp").to_string_lossy().into(),
        workspaces: vec![home.path().into()],
        ..Config::default()
    };
    std::fs::write(
        data.join("config.yaml"),
        serde_yaml::to_string(&config).unwrap(),
    )
    .unwrap();
    #[cfg(target_os = "macos")]
    let (base, definition) = (
        home.path().join("Library/Application Support/PinkCollab"),
        home.path()
            .join("Library/LaunchAgents/dev.pinkcollab.gateway.plist"),
    );
    #[cfg(target_os = "linux")]
    let (base, definition) = (
        home.path().join(".local/share/pinkcollab"),
        home.path().join(".config/systemd/user/pinkcollab.service"),
    );
    std::fs::create_dir_all(base.join("bin")).unwrap();
    std::fs::create_dir_all(definition.parent().unwrap()).unwrap();
    std::fs::write(&definition, "fake definition").unwrap();
    std::fs::write(
        base.join("bin/data-dir"),
        data.canonicalize().unwrap().to_string_lossy().as_bytes(),
    )
    .unwrap();
    executable(&home.path().join("bin/id"), "echo 501");
    executable(&home.path().join("bin/launchctl"), "echo 'state = running'");
    executable(&home.path().join("bin/systemctl"), "echo active");
    let server = std::thread::spawn(move || {
        for _ in 0..4 {
            let (mut socket, _) = listener.accept().unwrap();
            let mut request = [0; 1024];
            assert!(socket.read(&mut request).unwrap() > 0);
            socket
                .write_all(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\npinkcollab:ok")
                .unwrap();
        }
    });
    let bare = cli(home.path(), &data).output().unwrap();
    let status = cli(home.path(), &data).arg("status").output().unwrap();
    std::fs::copy(
        env!("CARGO_BIN_EXE_pinkcollab-gateway"),
        base.join("bin/pinkcollab-gateway"),
    )
    .unwrap();
    let current = cli(home.path(), &data).arg("doctor").output().unwrap();
    assert!(String::from_utf8_lossy(&current.stdout).contains("build: current"));
    std::fs::write(base.join("bin/pinkcollab-gateway"), "older build").unwrap();
    let old = cli(home.path(), &data).arg("doctor").output().unwrap();
    assert!(!old.status.success());
    assert!(String::from_utf8_lossy(&old.stdout).contains("from another PinkCollab build"));
    server.join().unwrap();
    assert!(
        bare.status.success(),
        "{}",
        String::from_utf8_lossy(&bare.stderr)
    );
    assert!(status.status.success());
    assert_eq!(bare.stdout, status.stdout);
    assert!(String::from_utf8_lossy(&bare.stdout).contains("Service       Running"));
}
