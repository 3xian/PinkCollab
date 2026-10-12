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

fn saved_omp_config(home: &Path, data: &Path, omp: &Path) -> Config {
    std::fs::create_dir_all(data).unwrap();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let config = Config {
        listen: listener.local_addr().unwrap(),
        omp: omp.to_string_lossy().into_owned(),
        public_url: "https://proxy.example".into(),
        name: "Keep this name".into(),
        omp_args: vec!["--keep-argument".into()],
        max_sessions: 17,
        workspaces: vec![home.canonicalize().unwrap()],
        ..Config::default()
    };
    std::fs::write(
        data.join("config.yaml"),
        serde_yaml::to_string(&config).unwrap(),
    )
    .unwrap();
    config
}

fn config_snapshot(data: &Path) -> (Vec<u8>, std::time::SystemTime) {
    let path = data.join("config.yaml");
    (
        std::fs::read(&path).unwrap(),
        path.metadata().unwrap().modified().unwrap(),
    )
}

fn migration_setup(home: &Path, data: &Path) -> Command {
    let mut command = cli(home, data);
    command
        .args([
            "setup",
            "--non-interactive",
            "--transport",
            "external",
            "--public-url",
            "https://proxy.example",
            "--workspace",
        ])
        .arg(home);
    command
}

#[test]
fn stale_omp_noninteractive_requires_authorization() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    let data = home.path().join("data");
    let stale = home.path().join("removed-installation/omp");
    saved_omp_config(home.path(), &data, &stale);
    let before = config_snapshot(&data);
    let output = migration_setup(home.path(), &data).output().unwrap();
    assert!(!output.status.success());
    let diagnostic = format!(
        "{} {}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(diagnostic.contains("setup --omp omp"), "{diagnostic}");
    assert!(diagnostic.contains(stale.to_str().unwrap()), "{diagnostic}");
    assert_eq!(config_snapshot(&data), before);
    assert!(!data.join("pinkcollab.db").exists());
    assert!(!home.path().join("Library").exists());
    assert!(!home.path().join(".config").exists());
}

#[test]
fn failed_configured_omp_is_probed_once_when_path_matches() {
    for bare_name in [false, true] {
        for doctor in [false, true] {
            let home = tempfile::tempdir().unwrap();
            tools(home.path());
            let entry = versioned_omp(home.path());
            executable(
                &home.path().join("bin/omp-0.1.0"),
                r#"printf '%s\n' "$1" >> "$HOME/omp-probes"; exit 23"#,
            );
            let data = home.path().join("data");
            saved_omp_config(
                home.path(),
                &data,
                if bare_name { Path::new("omp") } else { &entry },
            );
            let before = config_snapshot(&data);
            let output = if doctor {
                cli(home.path(), &data).arg("doctor").output().unwrap()
            } else {
                migration_setup(home.path(), &data).output().unwrap()
            };
            assert!(!output.status.success());
            assert_eq!(
                std::fs::read_to_string(home.path().join("omp-probes")).unwrap(),
                "--version\n",
                "doctor={doctor}, bare_name={bare_name}"
            );
            assert_eq!(config_snapshot(&data), before);
        }
    }
}

#[test]
fn stale_omp_invalid_candidates_leave_config_and_service_untouched() {
    for candidate in [
        None,
        Some("exit 23"),
        Some("printf '  \\n'"),
        Some("exec /bin/sleep 30"),
    ] {
        let home = tempfile::tempdir().unwrap();
        tools(home.path());
        match candidate {
            Some(script) => executable(&home.path().join("bin/omp"), script),
            None => std::fs::remove_file(home.path().join("bin/omp")).unwrap(),
        }
        let data = home.path().join("data");
        saved_omp_config(home.path(), &data, &home.path().join("removed/omp"));
        let before = config_snapshot(&data);
        #[cfg(not(target_os = "linux"))]
        let definition = home
            .path()
            .join("Library/LaunchAgents/dev.pinkcollab.gateway.plist");
        #[cfg(target_os = "linux")]
        let definition = home.path().join(".config/systemd/user/pinkcollab.service");
        #[cfg(target_os = "macos")]
        let base = home.path().join("Library/Application Support/PinkCollab");
        #[cfg(target_os = "linux")]
        let base = home.path().join(".local/share/pinkcollab");
        std::fs::create_dir_all(base.join("bin")).unwrap();
        std::fs::write(
            base.join("bin/data-dir"),
            data.canonicalize().unwrap().to_string_lossy().as_bytes(),
        )
        .unwrap();
        executable(&home.path().join("bin/id"), "echo 501");
        std::fs::create_dir_all(definition.parent().unwrap()).unwrap();
        std::fs::write(&definition, b"existing service definition").unwrap();
        let definition_before = (
            std::fs::read(&definition).unwrap(),
            definition.metadata().unwrap().modified().unwrap(),
        );
        // Any service-manager invocation is recorded, including an accidental restart.
        for tool in ["systemctl", "launchctl"] {
            executable(
                &home.path().join("bin").join(tool),
                r#"printf '%s\n' "$*" >> "$HOME/service-commands"
case "$1 $2" in
 "print "*) exit 1 ;;
 "--user show") echo inactive ;;
 *) echo unexpected-mutation >> "$HOME/service-commands"; exit 99 ;;
esac"#,
            );
        }
        let absolute = home.path().join("bin/omp");
        for override_reference in [None, Some("omp"), Some(absolute.to_str().unwrap())] {
            let mut command = migration_setup(home.path(), &data);
            if let Some(reference) = override_reference {
                command.args(["--omp", reference]);
            }
            let started = std::time::Instant::now();
            let output = command.output().unwrap();
            assert!(!output.status.success(), "{candidate:?}");
            let diagnostic = String::from_utf8_lossy(&output.stderr);
            assert!(diagnostic.contains("OMP"), "{diagnostic}");
            assert!(
                diagnostic.contains(if candidate.is_none() {
                    "missing or not executable"
                } else {
                    "failed `--version`"
                }),
                "{diagnostic}"
            );
            assert!(
                started.elapsed() < std::time::Duration::from_secs(15),
                "{candidate:?}"
            );
            assert_eq!(config_snapshot(&data), before, "{candidate:?}");
            assert_eq!(
                (
                    std::fs::read(&definition).unwrap(),
                    definition.metadata().unwrap().modified().unwrap()
                ),
                definition_before,
                "{candidate:?}"
            );
            let calls = std::fs::read_to_string(home.path().join("service-commands")).unwrap();
            assert!(!calls.contains("unexpected-mutation"), "{calls}");
            assert!(!data.join("pinkcollab.db").exists(), "{candidate:?}");
        }
    }
}

#[test]
fn valid_pinned_omp_does_not_execute_path_candidate() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    let pinned = home.path().join("pinned-omp");
    executable(&pinned, "echo pinned-version");
    executable(
        &home.path().join("bin/omp"),
        r#"echo probed > "$HOME/path-omp-probed"; exit 99"#,
    );
    let data = home.path().join("data");
    let expected = saved_omp_config(home.path(), &data, &pinned);
    let output = migration_setup(home.path(), &data).output().unwrap();
    // Missing fake service manager prevents completion after OMP preflight.
    assert!(!output.status.success());
    assert_eq!(Config::load(&data).unwrap().omp, expected.omp);
    assert!(!home.path().join("path-omp-probed").exists());
}

#[test]
fn stale_omp_doctor_reports_candidate_without_migrating() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    let data = home.path().join("data");
    let stale = home.path().join("removed/omp");
    saved_omp_config(home.path(), &data, &stale);
    let before = config_snapshot(&data);
    let output = cli(home.path(), &data).arg("doctor").output().unwrap();
    assert!(!output.status.success());
    let text = format!(
        "{} {}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(text.contains(stale.to_str().unwrap()), "{text}");
    assert!(
        text.contains(home.path().join("bin/omp").to_str().unwrap()),
        "{text}"
    );
    assert!(text.contains("0.1.0"), "{text}");
    assert!(text.contains("setup --omp omp"), "{text}");
    assert_eq!(config_snapshot(&data), before);
    assert!(!data.join("pinkcollab.db").exists());
    // Diagnostics must not migrate an older database either.
    let database = data.join("pinkcollab.db");
    let db = rusqlite::Connection::open(&database).unwrap();
    db.execute_batch("CREATE TABLE clients (id TEXT); PRAGMA user_version=1;")
        .unwrap();
    drop(db);
    let database_before = (
        std::fs::read(&database).unwrap(),
        database.metadata().unwrap().modified().unwrap(),
    );
    let output = cli(home.path(), &data).arg("doctor").output().unwrap();
    assert!(!output.status.success());
    assert_eq!(config_snapshot(&data), before);
    assert!(
        !String::from_utf8_lossy(&output.stderr).contains("Database leases"),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(
        (
            std::fs::read(&database).unwrap(),
            database.metadata().unwrap().modified().unwrap(),
        ),
        database_before
    );
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
        .args([
            "setup",
            "--transport",
            "tailscale",
            "--non-interactive",
            "--workspace",
        ])
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

/// The listener stays owned by this fixture, not a real Gateway or user service.
/// Nonblocking accepts and bounded reads make failed CLI assertions safe to unwind.
struct HealthFixture {
    address: std::net::SocketAddr,
    requests: std::sync::Arc<std::sync::atomic::AtomicUsize>,
    stop: std::sync::Arc<std::sync::atomic::AtomicBool>,
    server: Option<std::thread::JoinHandle<()>>,
}
impl HealthFixture {
    fn new() -> Self {
        use std::sync::{
            Arc,
            atomic::{AtomicBool, AtomicUsize, Ordering},
        };
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        listener.set_nonblocking(true).unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let requests = Arc::new(AtomicUsize::new(0));
        let server_stop = stop.clone();
        let server_requests = requests.clone();
        let server = std::thread::spawn(move || {
            while !server_stop.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut socket, _)) => {
                        socket
                            .set_read_timeout(Some(std::time::Duration::from_secs(1)))
                            .unwrap();
                        socket
                            .set_write_timeout(Some(std::time::Duration::from_secs(1)))
                            .unwrap();
                        let mut request = [0; 1024];
                        let count = socket.read(&mut request).unwrap();
                        assert!(request[..count].starts_with(b"GET /health HTTP/1.1\r\n"));
                        socket
                            .write_all(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\npinkcollab:ok")
                            .unwrap();
                        server_requests.fetch_add(1, Ordering::Relaxed);
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(std::time::Duration::from_millis(5));
                    }
                    Err(error) => panic!("health fixture accept failed: {error}"),
                }
            }
        });
        Self {
            address,
            requests,
            stop,
            server: Some(server),
        }
    }
}
impl Drop for HealthFixture {
    fn drop(&mut self) {
        self.stop.store(true, std::sync::atomic::Ordering::Relaxed);
        if let Some(server) = self.server.take() {
            let result = server.join();
            if !std::thread::panicking() {
                result.unwrap();
            }
        }
    }
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
#[test]
fn external_setup_reuses_service_flow_updates_url_and_pairs_without_tailscale() {
    let home = tempfile::tempdir().unwrap();
    tools(home.path());
    executable(
        &home.path().join("bin/tailscale"),
        r#"printf '%s\n' "$*" >> "$HOME/tailscale-commands"
exit 99"#,
    );
    let health = HealthFixture::new();
    let data = home.path().join("data");
    let store = Store::open(&data).unwrap();
    let pairing = store.new_pairing().unwrap();
    let (client_id, _) = store.pair(&pairing, "Existing phone").unwrap();
    let db = rusqlite::Connection::open(data.join("pinkcollab.db")).unwrap();
    db.execute(
        "INSERT INTO session_records (id,host_id,cwd,title,metadata_revision,created_at,updated_at,engine_session_ref) VALUES ('sess_existing',?1,?2,'Preserved task',3,'2026-01-01T00:00:00Z','2026-01-02T00:00:00Z','existing-transcript')",
        rusqlite::params![store.host_id().unwrap(), home.path().to_str().unwrap()],
    ).unwrap();
    drop(db);
    let pinned = home.path().join("pinned-omp");
    executable(&pinned, "echo pinned-version");
    let mut expected = Config {
        listen: health.address,
        public_url: "https://old-host.ts.net".into(),
        name: "Custom gateway name".into(),
        omp: home
            .path()
            .join("removed-installation/omp")
            .to_string_lossy()
            .into_owned(),
        omp_args: vec!["--custom-argument".into()],
        max_sessions: 17,
        workspaces: vec![home.path().canonicalize().unwrap()],
        ..Config::default()
    };
    std::fs::write(
        data.join("config.yaml"),
        serde_yaml::to_string(&expected).unwrap(),
    )
    .unwrap();
    std::fs::write(data.join("funnel-url"), &expected.public_url).unwrap();
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
    // A managed running fixture allows the reserved health port through preflight.
    // Installation still replaces the fake definition and stages the actual binary.
    std::fs::create_dir_all(base.join("bin")).unwrap();
    std::fs::create_dir_all(definition.parent().unwrap()).unwrap();
    std::fs::write(&definition, "fake old definition").unwrap();
    std::fs::write(
        base.join("bin/data-dir"),
        data.canonicalize().unwrap().to_string_lossy().as_bytes(),
    )
    .unwrap();
    executable(&home.path().join("bin/id"), "echo 501");
    executable(
        &home.path().join("bin/systemctl"),
        r#"printf '%s\n' "systemctl $*" >> "$HOME/service-commands"
case "$2" in
 show) echo active ;;
 stop) [ ! -f "$HOME/restart-fails" ] || exit 23 ;;
 start)
  while IFS= read -r line; do
   case "$line" in public_url:*) printf '%s\n' "$line" >> "$HOME/service-commands" ;; esac
  done < "$HOME/data/config.yaml" ;;
esac"#,
    );
    executable(
        &home.path().join("bin/launchctl"),
        r#"printf '%s\n' "launchctl $*" >> "$HOME/service-commands"
case "$1" in
 print) [ ! -s "$HOME/service-stopped" ] || exit 1; echo 'state = running' ;;
 bootout) [ ! -f "$HOME/restart-fails" ] || exit 23; echo stopped > "$HOME/service-stopped" ;;
 bootstrap)
  while IFS= read -r line; do
   case "$line" in public_url:*) printf '%s\n' "$line" >> "$HOME/service-commands" ;; esac
  done < "$HOME/data/config.yaml"
  : > "$HOME/service-stopped" ;;
esac"#,
    );
    let mut unchanged_config = None;
    for (index, (url, reference)) in [
        ("https://proxy.example:8443", Some("omp")),
        ("https://proxy.example:8443", Some(pinned.to_str().unwrap())),
        ("https://proxy.example:8443", None),
        ("https://replacement.example", None),
        ("https://replacement.example", None),
    ]
    .into_iter()
    .enumerate()
    {
        let before = health.requests.load(std::sync::atomic::Ordering::Relaxed);
        let before_config = config_snapshot(&data);
        let before_definition = (
            std::fs::read(&definition).unwrap(),
            definition.metadata().unwrap().modified().unwrap(),
        );
        let before_calls =
            std::fs::read_to_string(home.path().join("service-commands")).unwrap_or_default();
        let mut command = cli(home.path(), &data);
        command
            .args([
                "setup",
                "--non-interactive",
                "--transport",
                "external",
                "--public-url",
                url,
                "--workspace",
            ])
            .arg(home.path());
        if let Some(reference) = reference {
            command.args(["--omp", reference]);
        }
        let output = command.output().unwrap();
        assert!(
            output.status.success(),
            "{} {}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        assert!(health.requests.load(std::sync::atomic::Ordering::Relaxed) > before);
        expected.public_url = url.into();
        expected.omp = if index == 0 {
            home.path().join("bin/omp").to_string_lossy().into_owned()
        } else {
            pinned.to_string_lossy().into_owned()
        };
        let calls = std::fs::read_to_string(home.path().join("service-commands")).unwrap();
        let new_calls = &calls[before_calls.len()..];
        #[cfg(target_os = "macos")]
        let starts = new_calls
            .lines()
            .filter(|line| line.starts_with("launchctl bootstrap"))
            .count();
        #[cfg(target_os = "linux")]
        let starts = new_calls
            .lines()
            .filter(|line| *line == "systemctl --user start pinkcollab")
            .count();
        if index < 3 {
            assert_eq!(
                starts,
                usize::from(index < 2),
                "iteration {index}: {new_calls}"
            );
        }
        if index == 1 || index == 2 {
            assert_eq!(
                (
                    std::fs::read(&definition).unwrap(),
                    definition.metadata().unwrap().modified().unwrap()
                ),
                before_definition,
                "config-only migration must not rewrite the service definition"
            );
        }
        if index == 2 || index == 4 {
            assert_eq!(
                config_snapshot(&data),
                before_config,
                "repeat setup rewrote config"
            );
            assert_eq!(starts, 0, "repeat setup restarted service: {new_calls}");
        }
        assert_eq!(
            serde_yaml::to_string(&Config::load(&data).unwrap()).unwrap(),
            serde_yaml::to_string(&expected).unwrap()
        );
        assert!(!data.join("funnel-url").exists());
        assert!(!home.path().join("tailscale-commands").exists());
        let db = rusqlite::Connection::open(data.join("pinkcollab.db")).unwrap();
        let tokens: i64 = db
            .query_row("SELECT COUNT(*) FROM pairing", [], |row| row.get(0))
            .unwrap();
        assert_eq!(tokens, 0, "noninteractive setup created a pairing token");
        let clients = store.clients().unwrap();
        assert_eq!(clients.len(), 1);
        assert_eq!(clients[0].id, client_id);
        assert_eq!(clients[0].name, "Existing phone");
        let session = store.v2_session("sess_existing").unwrap().unwrap();
        assert_eq!(session.title, "Preserved task");
        assert_eq!(session.metadata_revision, 3);
        assert_eq!(
            session.engine_session_ref.as_deref(),
            Some("existing-transcript")
        );
        if url == "https://replacement.example" {
            let bytes = std::fs::read(data.join("config.yaml")).unwrap();
            if let Some(previous) = &unchanged_config {
                assert_eq!(&bytes, previous);
            }
            unchanged_config = Some(bytes);
        }
    }
    assert!(base.join("bin/pinkcollab-gateway").is_file());
    assert_ne!(
        std::fs::read_to_string(&definition).unwrap(),
        "fake old definition"
    );
    let calls = std::fs::read_to_string(home.path().join("service-commands")).unwrap();
    #[cfg(target_os = "linux")]
    for call in [
        "systemctl --user daemon-reload",
        "systemctl --user enable pinkcollab",
        "systemctl --user start pinkcollab",
    ] {
        assert!(calls.contains(call), "{calls}");
    }
    #[cfg(target_os = "macos")]
    for call in ["launchctl bootout", "launchctl bootstrap"] {
        assert!(calls.contains(call), "{calls}");
    }
    assert!(
        calls.contains("public_url: https://proxy.example:8443"),
        "{calls}"
    );
    assert!(
        calls.contains("public_url: https://replacement.example"),
        "{calls}"
    );
    for action in ["status", "doctor"] {
        let output = cli(home.path(), &data).arg(action).output().unwrap();
        assert!(
            output.status.success(),
            "{action}: {} {}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        assert!(String::from_utf8_lossy(&output.stdout).contains("Externally managed"));
    }
    assert!(!home.path().join("tailscale-commands").exists());
    let pair = cli(home.path(), &data).arg("pair").output().unwrap();
    assert!(
        pair.status.success(),
        "{}",
        String::from_utf8_lossy(&pair.stderr)
    );
    let payload: serde_json::Value = serde_json::from_slice(&pair.stdout).unwrap();
    assert_eq!(payload["version"], 1);
    assert_eq!(payload["url"], "https://replacement.example");
    store
        .pair(payload["token"].as_str().unwrap(), "External phone")
        .unwrap();
    // Authorized config is saved before restarting; a failed restart is not success.
    let replacement = home.path().join("replacement-omp");
    executable(&replacement, "echo replacement-version");
    std::fs::write(home.path().join("restart-fails"), b"fail").unwrap();
    let before_calls = std::fs::read_to_string(home.path().join("service-commands")).unwrap();
    let before_definition = (
        std::fs::read(&definition).unwrap(),
        definition.metadata().unwrap().modified().unwrap(),
    );
    let output = cli(home.path(), &data)
        .args([
            "setup",
            "--non-interactive",
            "--transport",
            "external",
            "--public-url",
            "https://replacement.example",
            "--workspace",
        ])
        .arg(home.path())
        .arg("--omp")
        .arg(&replacement)
        .output()
        .unwrap();
    assert!(
        !output.status.success(),
        "failed service restart was reported as success"
    );
    assert_eq!(
        Config::load(&data).unwrap().omp,
        replacement.to_str().unwrap()
    );
    assert_eq!(
        (
            std::fs::read(&definition).unwrap(),
            definition.metadata().unwrap().modified().unwrap()
        ),
        before_definition
    );
    let calls = std::fs::read_to_string(home.path().join("service-commands")).unwrap();
    let new_calls = &calls[before_calls.len()..];
    #[cfg(target_os = "macos")]
    {
        assert!(new_calls.contains("launchctl bootout"), "{new_calls}");
        assert!(!new_calls.contains("launchctl bootstrap"), "{new_calls}");
    }
    #[cfg(target_os = "linux")]
    {
        assert!(
            new_calls.contains("systemctl --user stop pinkcollab"),
            "{new_calls}"
        );
        assert!(
            !new_calls.contains("systemctl --user start pinkcollab"),
            "{new_calls}"
        );
    }
}
