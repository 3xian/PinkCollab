#![cfg(all(windows, feature = "test-fixtures"))]
//! Real Gateway crashes must reap OMP without losing the conversation or trusting old leases.
mod support;

use pinkcollab_gateway::{config::Config, storage::Store};
use serde_json::{Value, json};
use std::{
    path::PathBuf,
    process::{Child, Command, Stdio},
    time::Duration,
};
use support::{Harness, command_succeeds, create_session, runtime_generation, wait_operation};
use windows_sys::Win32::{
    Foundation::{CloseHandle, HANDLE, WAIT_OBJECT_0, WAIT_TIMEOUT},
    System::{
        JobObjects::CreateJobObjectW,
        Threading::{OpenProcess, PROCESS_TERMINATE, TerminateProcess, WaitForSingleObject},
    },
};

// Keep a handle to the exact process, rather than polling a PID that Windows could reuse.
struct Process(HANDLE);
impl Process {
    fn open(pid: u32) -> Self {
        // SYNCHRONIZE is a standard access right, not a process-specific permission.
        let handle = unsafe { OpenProcess(PROCESS_TERMINATE | 0x0010_0000, 0, pid) };
        assert!(
            !handle.is_null(),
            "cannot open fixture process {pid}: {}",
            std::io::Error::last_os_error()
        );
        Self(handle)
    }

    async fn wait_for_exit(&self) {
        tokio::time::timeout(Duration::from_secs(10), async {
            loop {
                match unsafe { WaitForSingleObject(self.0, 0) } {
                    WAIT_OBJECT_0 => return,
                    WAIT_TIMEOUT => tokio::time::sleep(Duration::from_millis(20)).await,
                    result => panic!("process wait failed: {result}"),
                }
            }
        })
        .await
        .expect("Gateway crash left an OMP fixture process alive");
    }
}
impl Drop for Process {
    fn drop(&mut self) {
        unsafe {
            if WaitForSingleObject(self.0, 0) == WAIT_TIMEOUT {
                // Failure cleanup only: assertions above must observe the unassisted exit.
                TerminateProcess(self.0, 1);
                WaitForSingleObject(self.0, 5_000);
            }
            CloseHandle(self.0);
        }
    }
}

struct NamedJob(HANDLE);
impl NamedJob {
    fn new(name: &str) -> Self {
        let wide: Vec<u16> = name.encode_utf16().chain(Some(0)).collect();
        let handle = unsafe { CreateJobObjectW(std::ptr::null(), wide.as_ptr()) };
        assert!(
            !handle.is_null(),
            "cannot create named job: {}",
            std::io::Error::last_os_error()
        );
        Self(handle)
    }
}
impl Drop for NamedJob {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.0);
        }
    }
}

struct Gateway {
    root: tempfile::TempDir,
    data: PathBuf,
    cwd: PathBuf,
    url: String,
    client: reqwest::Client,
    child: Option<Child>,
}
impl Gateway {
    fn new() -> Self {
        let root = tempfile::tempdir().unwrap();
        let data = root.path().join("data");
        let cwd = root.path().join("workspace");
        std::fs::create_dir_all(&data).unwrap();
        std::fs::create_dir_all(&cwd).unwrap();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let config = Config {
            listen: address,
            workspaces: vec![cwd.clone()],
            omp: env!("CARGO_BIN_EXE_omp-fixture").into(),
            // Unique transcript names expose the actual OMP PID. The descendant deliberately
            // survives stdin EOF, so only process-tree containment can reap it on a crash.
            omp_args: vec!["--lazy-history".into(), "--spawn-child-immediate".into()],
            max_sessions: 1,
            ..Config::default()
        };
        std::fs::write(
            data.join("config.yaml"),
            serde_yaml::to_string(&config).unwrap(),
        )
        .unwrap();
        Self {
            root,
            data,
            cwd,
            url: format!("http://{address}"),
            client: reqwest::Client::builder()
                .timeout(Duration::from_secs(5))
                .build()
                .unwrap(),
            child: None,
        }
    }

    fn store(&self) -> Store {
        Store::open(&self.data).unwrap()
    }

    async fn start(&mut self) {
        assert!(self.child.is_none());
        let log = std::fs::File::create(self.data.join("serve.log")).unwrap();
        self.child = Some(
            Command::new(env!("CARGO_BIN_EXE_pinkcollab-gateway"))
                .current_dir(self.root.path())
                .arg("--data-dir")
                .arg(&self.data)
                .arg("serve")
                .env("LOCALAPPDATA", self.root.path().join("local"))
                .env("APPDATA", self.root.path().join("roaming"))
                .env("HOME", self.root.path())
                .env("USERPROFILE", self.root.path())
                .stdin(Stdio::null())
                .stdout(Stdio::from(log.try_clone().unwrap()))
                .stderr(Stdio::from(log))
                .spawn()
                .unwrap(),
        );
        tokio::time::timeout(Duration::from_secs(15), async {
            loop {
                assert!(
                    self.child.as_mut().unwrap().try_wait().unwrap().is_none(),
                    "Gateway exited during startup: {}",
                    std::fs::read_to_string(self.data.join("serve.log")).unwrap_or_default()
                );
                if self
                    .client
                    .get(format!("{}/health", self.url))
                    .send()
                    .await
                    .is_ok_and(|response| response.status().is_success())
                {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(25)).await;
            }
        })
        .await
        .expect("Gateway never became healthy");
    }

    fn crash(&mut self) {
        let mut child = self.child.take().unwrap();
        // On Windows Child::kill uses TerminateProcess: no shutdown signal or lease cleanup.
        child.kill().unwrap();
        child.wait().unwrap();
    }

    async fn pair_and_create(&self) -> (String, String, String) {
        let store = self.store();
        let token = store.new_pairing().unwrap();
        let paired = self
            .client
            .post(format!("{}/api/v4/pair", self.url))
            .json(&json!({"token":token,"name":"crash-regression"}))
            .send()
            .await
            .unwrap();
        assert_eq!(paired.status(), 201);
        let paired: Value = paired.json().await.unwrap();
        let credential = paired["credential"].as_str().unwrap().to_owned();
        let created = self
            .client
            .post(format!("{}/api/v4/sessions", self.url))
            .bearer_auth(&credential)
            .json(
                &json!({"commandId":"create","hostId":store.host_id().unwrap(),
                "cwd":pinkcollab_gateway::workspace::display(&self.cwd)}),
            )
            .send()
            .await
            .unwrap();
        assert_eq!(created.status(), 201);
        let created: Value = created.json().await.unwrap();
        let id = created["id"].as_str().unwrap().to_owned();
        let session = format!("{}/api/v4/sessions/{id}", self.url);
        (credential, id, session)
    }

    async fn history(&self, session: &str, credential: &str) -> Value {
        self.client
            .get(format!("{session}/history"))
            .bearer_auth(credential)
            .send()
            .await
            .unwrap()
            .error_for_status()
            .unwrap()
            .json()
            .await
            .unwrap()
    }
}
impl Drop for Gateway {
    fn drop(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

#[tokio::test]
async fn hard_crash_reaps_omp_and_prompt_resumes_the_same_conversation() {
    let mut gateway = Gateway::new();
    gateway.start().await;
    let (credential, id, session) = gateway.pair_and_create().await;
    command_succeeds(
        &gateway.client,
        &session,
        &credential,
        "before-crash",
        json!({"type":"prompt","message":"conversation before crash"}),
    )
    .await;
    let original_generation = runtime_generation(&gateway.client, &session, &credential).await;
    let original = gateway.store().v2_session(&id).unwrap().unwrap();
    let transcript = original.engine_session_ref.as_ref().unwrap();
    let pid: u32 = std::path::Path::new(transcript)
        .file_stem()
        .unwrap()
        .to_str()
        .unwrap()
        .strip_prefix("fixture-session-")
        .unwrap()
        .parse()
        .unwrap();
    let omp = Process::open(pid);
    let descendant = Process::open(
        std::fs::read_to_string(gateway.cwd.join("child.pid"))
            .unwrap()
            .parse()
            .unwrap(),
    );
    let before = gateway.history(&session, &credential).await;
    let original_items = before["items"].as_array().unwrap();
    assert!(before.to_string().contains("conversation before crash"));

    gateway.crash();
    omp.wait_for_exit().await;
    descendant.wait_for_exit().await;
    // TerminateProcess must leave the durable lease behind: recovery, not shutdown, clears it.
    assert_eq!(
        gateway.store().runtime_leases().unwrap(),
        vec![(id.clone(), original_generation.clone())]
    );
    gateway.start().await;
    let inactive: Value = gateway
        .client
        .get(&session)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(inactive["runtime"].is_null());
    command_succeeds(
        &gateway.client,
        &session,
        &credential,
        "after-crash",
        json!({"type":"prompt","message":"conversation after crash"}),
    )
    .await;
    let new_generation = runtime_generation(&gateway.client, &session, &credential).await;
    assert_ne!(new_generation, original_generation);
    let resumed = gateway.store().v2_session(&id).unwrap().unwrap();
    assert_eq!(resumed.id, original.id);
    assert_eq!(resumed.engine_session_ref, original.engine_session_ref);
    let resumed_descendant = Process::open(
        std::fs::read_to_string(gateway.cwd.join("child.pid"))
            .unwrap()
            .parse()
            .unwrap(),
    );
    let after = gateway.history(&session, &credential).await;
    let items = after["items"].as_array().unwrap();
    for item in original_items {
        assert!(
            items.contains(item),
            "original history item disappeared: {item}"
        );
    }
    assert!(after.to_string().contains("conversation after crash"));
    assert_eq!(
        gateway.store().runtime_leases().unwrap(),
        vec![(id, new_generation)]
    );
    gateway.crash();
    resumed_descendant.wait_for_exit().await;
}

#[tokio::test]
async fn legacy_and_existing_empty_named_job_leases_remain_blocked() {
    for (generation, named) in [("legacy-generation", false), ("live-generation", true)] {
        let harness = Harness::new(1, vec!["--spawn-child-immediate".into()]).await;
        let client = reqwest::Client::new();
        let credential = harness.pair().await;
        let cwd = harness.cwd(generation);
        let session = create_session(&client, &harness, &credential, &cwd).await;
        let id = session.rsplit('/').next().unwrap();
        assert!(harness.store.reserve_runtime(id, generation).unwrap());
        let job_name = format!(
            "Global\\PinkCollab.{}",
            pinkcollab_gateway::storage::id("test_")
        );
        // Keep an empty job alive: absence of processes is not proof that its owner exited.
        let job = named.then(|| NamedJob::new(&job_name));
        if named {
            rusqlite::Connection::open(harness.dir.path().join("data/pinkcollab.db")).unwrap()
                .execute("UPDATE runtime_leases SET windows_job_name=?1 WHERE session_id=?2 AND generation=?3",
                    rusqlite::params![job_name, id, generation]).unwrap();
        }
        let command_id = format!("blocked-{generation}");
        let response = client
            .post(format!("{session}/commands"))
            .bearer_auth(&credential)
            .json(&json!({"commandId":command_id,"type":"prompt","message":"must not run"}))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 202);
        let failed = wait_operation(
            &client,
            &format!("{session}/operations/{command_id}"),
            &credential,
            "failed",
        )
        .await;
        assert_eq!(failed["error"]["code"], "runtime_start_failed");
        assert_eq!(
            harness.store.runtime_leases().unwrap(),
            vec![(id.into(), generation.into())]
        );
        assert!(
            harness
                .store
                .v2_session(id)
                .unwrap()
                .unwrap()
                .engine_session_ref
                .is_none()
        );
        assert!(
            !std::path::Path::new(&cwd).join("child.pid").exists(),
            "blocked lease spawned an OMP child"
        );
        drop(job);
        if named {
            // A distinct intent must retry the same inactive controller once the exact job
            // disappears; a failed receipt must neither clear evidence nor poison retries.
            command_succeeds(
                &client,
                &session,
                &credential,
                "job-owner-gone",
                json!({"type":"prompt","message":"safe after job disappears"}),
            )
            .await;
            let recovered_generation = runtime_generation(&client, &session, &credential).await;
            assert_ne!(recovered_generation, generation);
            assert_eq!(
                harness.store.runtime_leases().unwrap(),
                vec![(id.into(), recovered_generation.clone())]
            );
            command_succeeds(
                &client,
                &session,
                &credential,
                "stop",
                json!({"type":"stop_runtime","generation":recovered_generation}),
            )
            .await;
        }
    }
}
