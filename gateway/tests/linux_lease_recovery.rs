#![cfg(all(target_os = "linux", feature = "test-fixtures"))]
mod support;

use serde_json::{Value, json};
use std::{
    os::unix::process::CommandExt,
    process::{Child, Command, Stdio},
};
use support::{Harness, command_succeeds, create_session, runtime_generation};

struct Orphan(Child);
impl Orphan {
    fn reserve(harness: &Harness, id: &str) -> Self {
        let child = Command::new("/bin/sh")
            .args(["-c", "read line"])
            .process_group(0)
            .stdin(Stdio::piped())
            .spawn()
            .unwrap();
        let orphan = Self(child);
        assert!(harness.store.reserve_runtime(id, "orphan").unwrap());
        let db = rusqlite::Connection::open(harness.dir.path().join("data/pinkcollab.db")).unwrap();
        let boot = std::fs::read_to_string("/proc/sys/kernel/random/boot_id").unwrap();
        db.execute(
            "UPDATE runtime_leases SET linux_boot_id=?1,linux_process_group=?2 WHERE session_id=?3",
            rusqlite::params![boot.trim(), orphan.0.id(), id],
        )
        .unwrap();
        orphan
    }

    fn exit(&mut self) {
        drop(self.0.stdin.take());
        self.0.wait().unwrap();
    }
}
impl Drop for Orphan {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

#[tokio::test]
async fn deletion_recovers_exited_group_but_preserves_live_orphan() {
    let harness = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = harness.pair().await;
    let session = create_session(&client, &harness, &credential, &harness.cwd("delete")).await;
    let id = session.rsplit('/').next().unwrap();
    let mut orphan = Orphan::reserve(&harness, id);
    let blocked = client
        .delete(&session)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap();
    assert_eq!(blocked.status(), 409);
    assert_eq!(
        blocked.json::<Value>().await.unwrap()["code"],
        "runtime_exit_unconfirmed"
    );
    assert!(harness.store.v2_session(id).unwrap().is_some());
    assert_eq!(
        harness.store.runtime_leases().unwrap(),
        vec![(id.into(), "orphan".into())]
    );
    orphan.exit();
    assert_eq!(
        client
            .delete(&session)
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .status(),
        204
    );
    assert!(harness.store.v2_session(id).unwrap().is_none());
    assert!(harness.store.runtime_leases().unwrap().is_empty());
}

#[tokio::test]
async fn startup_recovers_exited_group_and_persists_new_process_identity() {
    let harness = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = harness.pair().await;
    let session = create_session(&client, &harness, &credential, &harness.cwd("resume")).await;
    let id = session.rsplit('/').next().unwrap();
    let mut orphan = Orphan::reserve(&harness, id);
    orphan.exit();
    command_succeeds(
        &client,
        &session,
        &credential,
        "resume",
        json!({"type":"start_runtime"}),
    )
    .await;
    let generation = runtime_generation(&client, &session, &credential).await;
    assert_ne!(generation, "orphan");
    let db = rusqlite::Connection::open(harness.dir.path().join("data/pinkcollab.db")).unwrap();
    let (boot, group): (String, i32) = db.query_row(
        "SELECT linux_boot_id,linux_process_group FROM runtime_leases WHERE session_id=?1 AND generation=?2",
        rusqlite::params![id, generation], |row| Ok((row.get(0)?, row.get(1)?)),
    ).unwrap();
    assert_eq!(
        boot,
        std::fs::read_to_string("/proc/sys/kernel/random/boot_id")
            .unwrap()
            .trim()
    );
    assert!(group > 0);
    assert_eq!(unsafe { libc::killpg(group, 0) }, 0);
    command_succeeds(
        &client,
        &session,
        &credential,
        "stop",
        json!({"type":"stop_runtime","generation":generation}),
    )
    .await;
    assert!(harness.store.runtime_leases().unwrap().is_empty());
    assert_eq!(
        client
            .delete(&session)
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .status(),
        204
    );
}
