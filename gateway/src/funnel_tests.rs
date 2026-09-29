use super::*;
use std::os::unix::fs::PermissionsExt;

struct Fixture {
    dir: tempfile::TempDir,
    binary: PathBuf,
    config: Config,
}
impl Fixture {
    fn new(
        https: u16,
        backend: u16,
        initial: serde_json::Value,
        applied: serde_json::Value,
        ending: &str,
    ) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let binary = dir.path().join("tailscale");
        let config = Config {
            listen: format!("127.0.0.1:{backend}").parse().unwrap(),
            public_url: if https == 443 {
                String::new()
            } else {
                public_url("host.ts.net", https)
            },
            ..Config::default()
        };
        std::fs::write(
            dir.path().join("config.yaml"),
            serde_yaml::to_string(&config).unwrap(),
        )
        .unwrap();
        std::fs::write(dir.path().join("initial"), initial.to_string()).unwrap();
        std::fs::write(dir.path().join("applied"), applied.to_string()).unwrap();
        let script = format!(
            r##"#!/bin/sh
cd "$(dirname "$0")" || exit 1
if [ "$1" = status ]; then
  echo '{{"BackendState":"Running","Self":{{"DNSName":"host.ts.net."}}}}'
elif [ "$2" = status ]; then
  if [ -f invoked ]; then cat applied; else cat initial; fi
else
  printf '%s\n' "$@" > invoked
  cp config.yaml before-apply
  {ending}
fi
"##
        );
        std::fs::write(&binary, script).unwrap();
        std::fs::set_permissions(&binary, std::fs::Permissions::from_mode(0o700)).unwrap();
        Self {
            dir,
            binary,
            config,
        }
    }
    fn run(&self, interaction: Interaction, timeout: Duration) -> Result<String> {
        reconcile_with_timeout(
            self.dir.path(),
            &self.config,
            &self.binary,
            interaction,
            timeout,
        )
    }
    fn unchanged(&self) {
        assert_eq!(
            std::fs::read_to_string(self.dir.path().join("config.yaml")).unwrap(),
            serde_yaml::to_string(&self.config).unwrap()
        );
        assert!(!self.dir.path().join("funnel-url").exists());
    }
}
fn mapping(https: u16, backend: u16) -> serde_json::Value {
    serde_json::json!({
        "TCP": {https.to_string(): {"HTTPS": true}},
        "Web": {format!("host.ts.net:{https}"): {"Handlers": {"/": {"Proxy": target(backend)}}}},
        "AllowFunnel": {format!("host.ts.net:{https}"): true}
    })
}
#[test]
fn execution_modes_ports_and_save_after_verification() {
    for mode in [Interaction::Allowed, Interaction::Forbidden] {
        for port in FUNNEL_PORTS {
            let fixture = Fixture::new(
                port,
                9876,
                serde_json::json!({}),
                mapping(port, 9876),
                "exit 0",
            );
            let public = fixture.run(mode, Duration::from_secs(3)).unwrap();
            assert_eq!(public, public_url("host.ts.net", port));
            let args = std::fs::read_to_string(fixture.dir.path().join("invoked")).unwrap();
            assert_eq!(args.contains("--yes"), mode == Interaction::Forbidden);
            assert!(args.contains(&format!("--https={port}")));
            assert!(args.contains("http://127.0.0.1:9876"));
            assert_eq!(
                std::fs::read_to_string(fixture.dir.path().join("before-apply")).unwrap(),
                serde_yaml::to_string(&fixture.config).unwrap()
            );
            assert_eq!(
                std::fs::read_to_string(fixture.dir.path().join("funnel-url")).unwrap(),
                public
            );
        }
    }
}
#[test]
fn correct_mapping_is_idempotent_and_wrong_existing_mapping_is_protected() {
    let fixture = Fixture::new(
        443,
        8787,
        mapping(443, 8787),
        serde_json::json!({}),
        "exit 99",
    );
    fixture
        .run(Interaction::Allowed, Duration::from_secs(3))
        .unwrap();
    fixture
        .run(Interaction::Forbidden, Duration::from_secs(3))
        .unwrap();
    assert!(!fixture.dir.path().join("invoked").exists());
    let fixture = Fixture::new(443, 8787, mapping(443, 9999), mapping(443, 8787), "exit 0");
    assert!(
        fixture
            .run(Interaction::Allowed, Duration::from_secs(3))
            .unwrap_err()
            .to_string()
            .contains("another application")
    );
    assert!(!fixture.dir.path().join("invoked").exists());
    fixture.unchanged();
}
#[test]
fn ambiguous_failure_and_timeout_require_exact_mapping() {
    for ending in ["exit 1", "sleep 3"] {
        for backend in [8787, 9999] {
            let fixture = Fixture::new(
                443,
                8787,
                serde_json::json!({}),
                mapping(443, backend),
                ending,
            );
            let result = fixture.run(Interaction::Forbidden, Duration::from_millis(150));
            assert_eq!(result.is_ok(), backend == 8787);
            if result.is_err() {
                fixture.unchanged();
            }
        }
    }
}
#[test]
fn unsuccessful_verification_never_persists_success() {
    for applied in [serde_json::json!({}), mapping(443, 9999)] {
        let fixture = Fixture::new(443, 8787, serde_json::json!({}), applied, "exit 0");
        assert!(
            fixture
                .run(Interaction::Forbidden, Duration::from_secs(3))
                .is_err()
        );
        fixture.unchanged();
    }
}
#[test]
fn approval_timeout_has_actual_ports_and_policy_keeps_specialized_error() {
    let fixture = Fixture::new(
        8443,
        9876,
        serde_json::json!({}),
        serde_json::json!({}),
        "sleep 3",
    );
    let error = format!(
        "{:#}",
        fixture
            .run(Interaction::Forbidden, Duration::from_millis(150))
            .unwrap_err()
    );
    assert!(error.contains("one-time Funnel/HTTPS approval"));
    assert!(error.contains("tailscale funnel --bg --https=8443 http://127.0.0.1:9876"));
    assert!(error.contains("setup --non-interactive"));
    fixture.unchanged();
    let fixture = Fixture::new(
        443,
        8787,
        serde_json::json!({}),
        serde_json::json!({}),
        r#"echo 'Funnel not available; "funnel" node attribute not set. See https://tailscale.com/s/no-funnel.' >&2; exit 1"#,
    );
    let error = fixture
        .run(Interaction::Forbidden, Duration::from_secs(3))
        .unwrap_err();
    let error = format!("{error:#}");
    assert!(error.contains("not allowed for this device"));
    fixture.unchanged();
}

#[test]
fn standalone_and_dry_run_keep_their_cli_contract() {
    let fixture = Fixture::new(
        10000,
        9876,
        serde_json::json!({}),
        mapping(10000, 9876),
        "exit 0",
    );
    let options = |dry_run| Options {
        https_port: 10000,
        dry_run,
        binary: Some(&fixture.binary),
    };
    assert!(
        setup(fixture.dir.path(), &fixture.config, options(true))
            .unwrap()
            .is_none()
    );
    assert!(!fixture.dir.path().join("invoked").exists());
    fixture.unchanged();
    assert_eq!(
        setup(fixture.dir.path(), &fixture.config, options(false))
            .unwrap()
            .unwrap(),
        "https://host.ts.net:10000"
    );
    assert!(
        std::fs::read_to_string(fixture.dir.path().join("invoked"))
            .unwrap()
            .contains("--yes")
    );
}
#[test]
fn externally_managed_address_is_not_replaced() {
    let mut fixture = Fixture::new(
        443,
        8787,
        serde_json::json!({}),
        mapping(443, 8787),
        "exit 0",
    );
    fixture.config.public_url = "https://gateway.example.com".into();
    assert!(
        fixture
            .run(Interaction::Forbidden, Duration::from_secs(3))
            .unwrap_err()
            .to_string()
            .contains("another transport")
    );
    assert!(!fixture.dir.path().join("invoked").exists());
}

#[test]
fn interruption_never_recovers_even_when_mapping_was_committed() {
    for ending in ["kill -INT $$", "exit 130"] {
        let fixture = Fixture::new(443, 8787, serde_json::json!({}), mapping(443, 8787), ending);
        let error = fixture
            .run(Interaction::Allowed, Duration::from_secs(3))
            .unwrap_err();
        assert!(error.is::<crate::command::Interrupted>());
        fixture.unchanged();
    }
}
#[test]
fn standalone_failure_preserves_recovery_entry_and_port() {
    let fixture = Fixture::new(
        8443,
        9876,
        serde_json::json!({}),
        serde_json::json!({}),
        "exit 1",
    );
    let error = setup(
        fixture.dir.path(),
        &fixture.config,
        Options {
            https_port: 8443,
            dry_run: false,
            binary: Some(&fixture.binary),
        },
    )
    .unwrap_err();
    let text = format!("{error:#}");
    assert!(text.contains("pinkcollab funnel --https=8443"));
    assert!(text.contains("--data-dir, --tailscale"));
    assert!(!text.contains("pinkcollab setup"));
    fixture.unchanged();
}
