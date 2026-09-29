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
fn version_and_help_explain_the_product_entry_points() {
    let executable = env!("CARGO_BIN_EXE_pinkcollab-gateway");
    let version = Command::new(executable).arg("--version").output().unwrap();
    assert!(version.status.success());
    assert_eq!(
        String::from_utf8_lossy(&version.stdout).trim(),
        format!("pinkcollab {}", env!("CARGO_PKG_VERSION"))
    );
    let help = Command::new(executable).arg("--help").output().unwrap();
    let help = String::from_utf8_lossy(&help.stdout);
    assert!(help.contains("foreground (advanced/manual)"));
    assert!(help.find("  setup").unwrap() < help.find("  serve").unwrap());
}
