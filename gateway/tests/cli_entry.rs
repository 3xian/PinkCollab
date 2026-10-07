//! Entry-point checks run on every supported operating system, without service access.
use std::process::Command;
#[test]
fn unconfigured_entry_is_read_only() {
    let root = tempfile::tempdir().unwrap();
    let data = root.path().join("missing data");
    let output = Command::new(env!("CARGO_BIN_EXE_pinkcollab-gateway"))
        .arg("--data-dir")
        .arg(&data)
        .output()
        .unwrap();
    assert!(output.status.success());
    assert!(String::from_utf8_lossy(&output.stdout).contains("pinkcollab setup"));
    assert_eq!(std::fs::read_dir(root.path()).unwrap().count(), 0);
}
#[test]
fn version_is_branded() {
    let executable = env!("CARGO_BIN_EXE_pinkcollab-gateway");
    let version = Command::new(executable).arg("--version").output().unwrap();
    assert!(version.status.success());
    assert_eq!(
        String::from_utf8_lossy(&version.stdout).trim(),
        format!("pinkcollab {}", env!("CARGO_PKG_VERSION"))
    );
}

#[test]
fn invalid_setup_transport_arguments_fail_before_writing_configuration() {
    let cases: &[&[&str]] = &[
        &["--transport", "external"],
        &["--transport", "unsupported"],
        &["--transport", "external", "--public-url", ""],
        &[
            "--transport",
            "tailscale",
            "--public-url",
            "https://proxy.example",
        ],
        &["--transport", "external", "--public-url", "not-a-url"],
        &[
            "--transport",
            "external",
            "--public-url",
            "http://proxy.example",
        ],
        &[
            "--transport",
            "external",
            "--public-url",
            "ftp://proxy.example",
        ],
        &[
            "--transport",
            "external",
            "--public-url",
            "https://user:secret@proxy.example",
        ],
        &[
            "--transport",
            "external",
            "--public-url",
            "https://proxy.example/path",
        ],
        &[
            "--transport",
            "external",
            "--public-url",
            "https://proxy.example/?query=yes",
        ],
        &[
            "--transport",
            "external",
            "--public-url",
            "https://proxy.example/#fragment",
        ],
        &["--transport", "external", "--public-url", "https://"],
    ];
    for args in cases {
        let root = tempfile::tempdir().unwrap();
        let home = root.path().join("home");
        let bin = root.path().join("bin");
        std::fs::create_dir(&home).unwrap();
        std::fs::create_dir(&bin).unwrap();
        let data = root.path().join("missing data");
        let output = Command::new(env!("CARGO_BIN_EXE_pinkcollab-gateway"))
            .env("HOME", &home)
            .env("USERPROFILE", &home)
            .env("LOCALAPPDATA", &home)
            .env("APPDATA", &home)
            .env("PATH", &bin)
            .arg("--data-dir")
            .arg(&data)
            .args(["setup", "--non-interactive", "--workspace"])
            .arg(root.path())
            .args(*args)
            .output()
            .unwrap();
        assert!(
            !output.status.success(),
            "{args:?}: {}",
            String::from_utf8_lossy(&output.stdout)
        );
        let error = String::from_utf8_lossy(&output.stderr);
        assert!(
            error
                .to_ascii_lowercase()
                .contains(if args[1] == "unsupported" {
                    "transport"
                } else {
                    "url"
                }),
            "{args:?}: {error}"
        );
        assert!(!data.exists(), "{args:?} created the data directory");
        assert_eq!(std::fs::read_dir(&home).unwrap().count(), 0, "{args:?}");
    }
}
