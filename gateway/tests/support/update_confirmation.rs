use std::{
    path::Path,
    process::{Command, Stdio},
    time::Duration,
};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

pub async fn assert_owner_change_refuses_restart(cli: Command, owner: &Path, other: &Path) {
    let mut update = tokio::process::Command::from(cli)
        .arg("update")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .unwrap();

    // The flushed prompt has no trailing newline. Do not hand over ownership until
    // installation has completed and the CLI is waiting for its answer.
    let mut stdout = BufReader::new(update.stdout.take().unwrap());
    let mut prompt = Vec::new();
    tokio::time::timeout(
        Duration::from_secs(60),
        stdout.read_until(b'?', &mut prompt),
    )
    .await
    .expect("update did not prompt")
    .unwrap();
    assert!(prompt.ends_with(b"Restart the Gateway service now?"));
    std::fs::create_dir(other).unwrap();
    std::fs::write(
        owner,
        other.canonicalize().unwrap().to_string_lossy().as_bytes(),
    )
    .unwrap();
    update
        .stdin
        .take()
        .unwrap()
        .write_all(b"yes\n")
        .await
        .unwrap();

    let output = update.wait_with_output().await.unwrap();
    assert!(!output.status.success());
    let error = String::from_utf8_lossy(&output.stderr);
    assert!(error.contains("another data directory"), "{error}");
}
