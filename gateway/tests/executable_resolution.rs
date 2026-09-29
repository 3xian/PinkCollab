//! Executable lookup stores absolute paths without resolving symlink targets.
use pinkcollab_gateway::config::{Config, resolve_executable};
use std::{path::Path, process::Command};

// PATH and PATHEXT are process-global. Run their assertions in a dedicated test process so
// parallel integration tests (and the runner's own environment) cannot be affected.
fn isolated(case: &str, pathext: Option<&str>) {
    let root = tempfile::tempdir().unwrap();
    let mut command = Command::new(std::env::current_exe().unwrap());
    command
        .args(["--exact", "resolution_worker", "--nocapture"])
        .env("PINKCOLLAB_RESOLUTION_CASE", case)
        .env("PINKCOLLAB_RESOLUTION_ROOT", root.path())
        .current_dir(root.path())
        .env("PATH", root.path().join("bin"));
    if let Some(pathext) = pathext {
        command.env("PATHEXT", pathext);
    } else {
        command.env_remove("PATHEXT");
    }
    let result = command.output().unwrap();
    assert!(
        result.status.success(),
        "case {case}: stdout: {}\nstderr: {}",
        String::from_utf8_lossy(&result.stdout),
        String::from_utf8_lossy(&result.stderr)
    );
}

#[test]
fn resolution_worker() {
    let Ok(case) = std::env::var("PINKCOLLAB_RESOLUTION_CASE") else {
        return;
    };
    let root = std::path::PathBuf::from(std::env::var_os("PINKCOLLAB_RESOLUTION_ROOT").unwrap());
    let bin = root.join("bin");
    std::fs::create_dir(&bin).unwrap();
    match case.as_str() {
        #[cfg(unix)]
        "symlink" => {
            let real = root.join("real");
            unix_executable(&real, "exit 0");
            let link = bin.join("omp");
            std::os::unix::fs::symlink(&real, &link).unwrap();
            assert_eq!(resolve_executable("omp"), Some(link));
            assert_eq!(
                resolve_executable("./bin/omp"),
                Some(std::env::current_dir().unwrap().join("bin/omp"))
            );
        }
        #[cfg(unix)]
        "upgrade" => upgrade_and_spawn(&root, &bin),
        #[cfg(unix)]
        "broken_explicit" => {
            unix_executable(&bin.join("omp"), "exit 0");
            let broken = root.join("omp");
            std::os::unix::fs::symlink(root.join("missing"), &broken).unwrap();
            assert_eq!(resolve_executable(broken.to_str().unwrap()), None);
            assert_explicit_spawn_fails(&root, &broken);
        }
        #[cfg(windows)]
        "broken_explicit" => {
            std::fs::write(bin.join("omp.exe"), b"exe").unwrap();
            let broken = root.join("missing").join("omp.exe");
            assert_eq!(resolve_executable(broken.to_str().unwrap()), None);
            assert_explicit_spawn_fails(&root, &broken);
        }
        #[cfg(windows)]
        "extension_order" => {
            std::fs::write(bin.join("omp.CMD"), b"cmd").unwrap();
            std::fs::write(bin.join("omp.EXE"), b"exe").unwrap();
            assert_eq!(resolve_executable("omp"), Some(bin.join("omp.CMD")));
        }
        #[cfg(windows)]
        "default_extensions" => {
            std::fs::write(bin.join("omp.COM"), b"com").unwrap();
            std::fs::write(bin.join("omp.EXE"), b"exe").unwrap();
            assert_eq!(resolve_executable("omp"), Some(bin.join("omp.COM")));
        }
        #[cfg(windows)]
        "already_suffixed" => {
            std::fs::write(bin.join("omp.exe"), b"exe").unwrap();
            assert_eq!(resolve_executable("omp.exe"), Some(bin.join("omp.exe")));
        }
        other => panic!("unexpected isolated case: {other}"),
    }
}

fn assert_explicit_spawn_fails(root: &Path, executable: &Path) {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let _entered = runtime.enter();
    assert!(
        pinkcollab_gateway::omp::Runtime::spawn(executable.to_str().unwrap(), &[], root).is_err(),
        "an explicit missing executable must not fall back to a matching PATH entry"
    );
}

#[cfg(unix)]
fn unix_executable(path: &Path, body: &str) {
    use std::os::unix::fs::PermissionsExt;
    std::fs::write(path, format!("#!/bin/sh\n{body}\n")).unwrap();
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700)).unwrap();
}

#[cfg(unix)]
#[test]
fn path_lookup_retains_symlink_instead_of_canonicalizing_target() {
    isolated("symlink", None);
}

#[cfg(unix)]
#[test]
fn persisted_path_survives_symlink_target_upgrade() {
    isolated("upgrade", None);
}

#[cfg(unix)]
fn upgrade_and_spawn(root: &Path, bin: &Path) {
    use pinkcollab_gateway::omp::Runtime;
    use std::os::unix::fs::symlink;

    let first = root.join("first");
    let second = root.join("second");
    for (target, version) in [(&first, "first"), (&second, "second")] {
        unix_executable(
            target,
            &format!(
                "if [ \"$1\" = \"--version\" ]; then printf '%s\\n' '{version}'; exit 0; fi\nprintf '{version}' > runtime-target\nprintf '%s\\n' '{{\"type\":\"ready\"}}'\nwhile IFS= read -r line; do :; done"
            ),
        );
    }
    let link = bin.join("omp");
    symlink(&first, &link).unwrap();
    // Persist the absolute symlink entry, not the target or a bare name.
    let retained = resolve_executable("omp").unwrap();
    assert_eq!(retained, link);
    let config = Config {
        workspaces: vec![root.to_path_buf()],
        omp: retained.to_str().unwrap().to_owned(),
        ..Config::default()
    };
    std::fs::write(
        root.join("config.yaml"),
        serde_yaml::to_string(&config).unwrap(),
    )
    .unwrap();
    let persisted = Config::load(root).unwrap().omp;
    assert_eq!(persisted, retained.to_str().unwrap());

    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    runtime.block_on(async {
        assert_eq!(pinkcollab_gateway::omp::version(&persisted).await, "first");
        let (process, _output) = Runtime::spawn(&persisted, &[], root).unwrap();
        process.wait_ready().await.unwrap();
        assert_eq!(
            std::fs::read_to_string(root.join("runtime-target")).unwrap(),
            "first"
        );
        process.stop_confirmed().await.unwrap();

        // Atomic rename leaves the configured PATH entry untouched while moving the link.
        let replacement = bin.join("replacement");
        symlink(&second, &replacement).unwrap();
        std::fs::rename(replacement, &link).unwrap();
        std::fs::remove_file(&first).unwrap();
        assert_eq!(pinkcollab_gateway::omp::version(&persisted).await, "second");
        let (process, _output) = Runtime::spawn(&persisted, &[], root).unwrap();
        process.wait_ready().await.unwrap();
        assert_eq!(
            std::fs::read_to_string(root.join("runtime-target")).unwrap(),
            "second"
        );
        process.stop_confirmed().await.unwrap();
    });
}

#[test]
fn explicit_paths_preserve_symlinks_without_falling_back_to_path() {
    let root = tempfile::tempdir().unwrap();
    let bin = root.path().join("bin");
    std::fs::create_dir(&bin).unwrap();
    #[cfg(unix)]
    {
        let target = root.path().join("real");
        unix_executable(&target, "exit 0");
        let link = bin.join("omp");
        std::os::unix::fs::symlink(&target, &link).unwrap();
        assert_eq!(resolve_executable(link.to_str().unwrap()), Some(link));
    }
    #[cfg(windows)]
    {
        let executable = bin.join("omp.exe");
        std::fs::write(&executable, b"exe").unwrap();
        let explicit = bin.join("..").join("bin").join("omp.exe");
        assert_eq!(
            resolve_executable(explicit.to_str().unwrap()),
            Some(executable)
        );
    }
    isolated("broken_explicit", None);
}

#[cfg(windows)]
#[test]
fn pathext_uses_environment_order() {
    isolated("extension_order", Some(".CMD;.EXE"));
}

#[cfg(windows)]
#[test]
fn pathext_defaults_when_unset() {
    isolated("default_extensions", None);
}

#[cfg(windows)]
#[test]
fn already_suffixed_name_is_found_on_path() {
    isolated("already_suffixed", Some(".CMD;.EXE"));
}
