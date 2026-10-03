#![cfg(feature = "test-fixtures")]
mod support;
use pinkcollab_gateway::{
    discovery::Discovery,
    events::Bus,
    runtime::{Command, SessionDirectory},
    storage::Store,
    workspace::{self, Browser},
};
use serde_json::{Value, json};
use std::{path::Path, sync::Arc};
use support::{Harness, wait_operation};

fn transcript(path: &Path, cwd: &Path, id: &str) {
    let mut slot = json!({"type":"title","v":1,"title":"Existing conversation","updatedAt":"2026-01-01T00:00:00Z","pad":""});
    slot["pad"] = json!(" ".repeat(255 - slot.to_string().len()));
    assert_eq!(slot.to_string().len() + 1, 256);
    let entries = [
        slot,
        json!({"type":"session","version":3,"id":id,"cwd":workspace::display(&cwd.canonicalize().unwrap_or_else(|_| cwd.to_owned())),"timestamp":"2026-01-01T00:00:00Z"}),
        json!({"type":"message","id":"m1","parentId":null,"timestamp":"2026-01-01T00:00:01Z","message":{"role":"user","content":[{"type":"text","text":"original question"}]}}),
        json!({"type":"message","id":"m2","parentId":"m1","timestamp":"2026-01-01T00:00:02Z","message":{"role":"assistant","content":[{"type":"text","text":"original answer"}]}}),
    ];
    std::fs::write(
        path,
        entries.iter().map(|e| format!("{e}\n")).collect::<String>(),
    )
    .unwrap();
}
fn args(root: &Path) -> Vec<String> {
    vec!["--session-dir".into(), workspace::display(root)]
}

#[tokio::test]
async fn mixed_keyset_pages_survive_adoption_between_requests() {
    let root = tempfile::tempdir().unwrap();
    let files = tempfile::tempdir().unwrap();
    let data = tempfile::tempdir().unwrap();
    for i in 0..4 {
        let path = files.path().join(format!("{i}.jsonl"));
        transcript(&path, root.path(), &format!("external-{i}"));
        let body = std::fs::read_to_string(&path)
            .unwrap()
            .replace("2026-01-01T00:00:00Z", &format!("2026-01-01T00:00:0{i}Z"));
        std::fs::write(path, body).unwrap();
    }
    let browser = Arc::new(Browser::new(&[root.path().into()]).unwrap());
    let sources = Discovery::new(&args(files.path())).list(&browser);
    let store = Arc::new(Store::open(data.path()).unwrap());
    for (i, source) in sources.iter().enumerate() {
        let mut managed = source.record(store.host_id().unwrap());
        managed.id = format!("managed-{i}");
        managed.created_at += chrono::Duration::milliseconds(500);
        managed.engine_session_ref = None;
        store
            .create_v2("client", &managed.id.clone(), "create", managed)
            .unwrap();
    }
    let directory = SessionDirectory::new(
        store.clone(),
        browser,
        Arc::new(Bus::default()),
        "unused".into(),
        args(files.path()),
        1,
    );
    let expected: Vec<_> = directory
        .list()
        .await
        .unwrap()
        .into_iter()
        .map(|s| s.session.id)
        .collect();
    let mut cursor: Option<(String, String)> = None;
    let mut seen = Vec::new();
    loop {
        let (page, more) = directory
            .list_page(cursor.as_ref().map(|(t, id)| (t.as_str(), id.as_str())), 2)
            .await
            .unwrap();
        assert!(page.len() <= 2);
        if let Some(last) = page.last() {
            cursor = Some((
                last.session.created_at.to_rfc3339(),
                last.session.id.clone(),
            ));
        }
        seen.extend(page.into_iter().map(|s| s.session.id));
        if seen.len() == 2 {
            store.adopt(&sources[0]).unwrap();
        }
        if !more {
            break;
        }
    }
    assert_eq!(seen, expected);
    assert_eq!(seen.len(), 8);
}

async fn get(client: &reqwest::Client, url: &str, token: &str) -> Value {
    let response = client.get(url).bearer_auth(token).send().await.unwrap();
    assert_eq!(response.status(), 200, "{url}");
    response.json().await.unwrap()
}

#[tokio::test]
async fn external_history_first_prompt_adopts_once_and_resumes_original_transcript() {
    let files = tempfile::tempdir().unwrap();
    let h = Harness::new(1, args(files.path())).await;
    let path = files.path().join("external.jsonl");
    transcript(&path, Path::new(&h.cwd("external")), "external-1");
    let before = std::fs::read(&path).unwrap();
    let client = reqwest::Client::new();
    let token = h.pair().await;
    let list_url = format!("{}/api/v4/sessions", h.url);
    let list = get(&client, &list_url, &token).await;
    let discovered = &list["sessions"][0];
    assert_eq!(discovered["session"]["origin"], "discovered");
    assert!(discovered["runtime"].is_null());
    assert!(!discovered.to_string().contains("external.jsonl"));
    assert!(h.store.v2_sessions().unwrap().is_empty());
    let id = discovered["session"]["id"].as_str().unwrap();
    let url = format!("{list_url}/{id}");
    let detail = get(&client, &url, &token).await;
    assert_eq!(detail["hasHistory"], true);
    assert_eq!(detail["session"]["title"], "Existing conversation");
    let history_url = format!("{url}/history");
    let page = get(&client, &format!("{history_url}?limit=1"), &token).await;
    assert_eq!(page["items"][0]["text"], "original answer");
    let cursor = page["nextCursor"].as_str().unwrap();
    let older = get(
        &client,
        &format!("{history_url}?limit=1&cursor={cursor}"),
        &token,
    )
    .await;
    assert_eq!(older["items"][0]["text"], "original question");
    assert_eq!(std::fs::read(&path).unwrap(), before);
    // Same receipt racing itself must dispatch exactly once, including lazy ownership.
    let post = || {
        client
            .post(format!("{url}/commands"))
            .bearer_auth(&token)
            .json(&json!({"commandId":"first","type":"prompt","message":"continue"}))
            .send()
    };
    let (one, two) = tokio::join!(post(), post());
    assert!(one.unwrap().status().is_success());
    assert!(two.unwrap().status().is_success());
    wait_operation(
        &client,
        &format!("{url}/operations/first"),
        &token,
        "succeeded",
    )
    .await;
    let list = get(&client, &list_url, &token).await;
    assert_eq!(list["sessions"].as_array().unwrap().len(), 1);
    assert_eq!(list["sessions"][0]["session"]["id"], id);
    assert_eq!(list["sessions"][0]["session"]["origin"], "managed");
    assert_eq!(h.store.v2_sessions().unwrap().len(), 1);
    assert_eq!(
        h.store.adopted_omp_id(id).unwrap().as_deref(),
        Some("external-1")
    );
    assert!(
        !h.store
            .reference_is_unwritten(id, &workspace::display(&path))
            .unwrap()
    );
    assert!(std::fs::read(&path).unwrap().starts_with(&before));
    let history = get(&client, &history_url, &token).await;
    let texts = history["items"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|i| i["text"].as_str())
        .collect::<Vec<_>>();
    assert!(texts.contains(&"original question") && texts.contains(&"original answer"));
    assert_eq!(texts.iter().filter(|t| **t == "continue").count(), 1);
    let detail = get(&client, &url, &token).await;
    client.post(format!("{url}/commands")).bearer_auth(&token).json(&json!({"commandId":"stop","type":"stop_runtime","generation":detail["runtime"]["generation"]})).send().await.unwrap();
    wait_operation(
        &client,
        &format!("{url}/operations/stop"),
        &token,
        "succeeded",
    )
    .await;
    let reopened = Store::open(&h.dir.path().join("data")).unwrap();
    assert_eq!(
        reopened.adopted_omp_id(id).unwrap().as_deref(),
        Some("external-1")
    );
    assert_eq!(reopened.v2_sessions().unwrap().len(), 1);
    // Replacing the same filename cannot substitute unrelated history after adoption.
    transcript(&path, Path::new(&h.cwd("external")), "replacement");
    let response = client
        .get(&history_url)
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 503);
    assert_eq!(
        response.json::<Value>().await.unwrap()["code"],
        "history_unavailable"
    );
}

#[tokio::test]
async fn corrupt_and_deleted_sources_fail_without_creating_empty_sessions() {
    let files = tempfile::tempdir().unwrap();
    let h = Harness::new(1, args(files.path())).await;
    let path = files.path().join("external.jsonl");
    transcript(&path, Path::new(&h.cwd("external")), "broken");
    use std::io::Write;
    writeln!(
        std::fs::OpenOptions::new()
            .append(true)
            .open(&path)
            .unwrap(),
        "broken json"
    )
    .unwrap();
    std::fs::write(files.path().join("bad-header.jsonl"), "bad").unwrap();
    let client = reqwest::Client::new();
    let token = h.pair().await;
    let list_url = format!("{}/api/v4/sessions", h.url);
    let list = get(&client, &list_url, &token).await;
    assert_eq!(list["sessions"].as_array().unwrap().len(), 1);
    let id = list["sessions"][0]["session"]["id"].as_str().unwrap();
    let url = format!("{list_url}/{id}");
    assert_eq!(
        client
            .get(format!("{url}/history"))
            .bearer_auth(&token)
            .send()
            .await
            .unwrap()
            .status(),
        503
    );
    assert_eq!(
        client
            .post(format!("{url}/commands"))
            .bearer_auth(&token)
            .json(&json!({"commandId":"bad","type":"prompt","message":"hi"}))
            .send()
            .await
            .unwrap()
            .status(),
        503
    );
    assert!(h.store.v2_sessions().unwrap().is_empty());
    std::fs::remove_file(path).unwrap();
    assert!(
        get(&client, &list_url, &token).await["sessions"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        client
            .get(&url)
            .bearer_auth(&token)
            .send()
            .await
            .unwrap()
            .status(),
        404
    );
    assert_eq!(
        client
            .post(format!("{url}/commands"))
            .bearer_auth(&token)
            .json(&json!({"commandId":"gone","type":"prompt","message":"hi"}))
            .send()
            .await
            .unwrap()
            .status(),
        404
    );
    assert!(h.store.v2_sessions().unwrap().is_empty());
}

#[test]
fn allowlist_identity_restart_duplicates_and_bounded_depth() {
    let root = tempfile::tempdir().unwrap();
    let outside = tempfile::tempdir().unwrap();
    let files = tempfile::tempdir().unwrap();
    let browser = Browser::new(&[root.path().into()]).unwrap();
    transcript(&files.path().join("allowed.jsonl"), root.path(), "allowed");
    transcript(
        &files.path().join("outside.jsonl"),
        outside.path(),
        "outside",
    );
    transcript(
        &files.path().join("relative.jsonl"),
        Path::new("relative"),
        "relative",
    );
    transcript(
        &files.path().join("missing.jsonl"),
        &root.path().join("gone"),
        "missing",
    );
    std::fs::create_dir_all(files.path().join("nested/deep")).unwrap();
    transcript(
        &files.path().join("nested/deep/hidden.jsonl"),
        root.path(),
        "deep",
    );
    let mut discovery = Discovery::new(&args(files.path()));
    let first = discovery.list(&browser);
    assert_eq!(first.len(), 1);
    let again = Discovery::new(&args(files.path())).list(&browser);
    assert_eq!(first[0].id, again[0].id);
    assert_eq!(first[0].id, discovery.list(&browser)[0].id);
    let removed = Browser::new(&[outside.path().into()]).unwrap();
    assert!(discovery.resolve(&first[0].id, &removed).unwrap().is_none());
    std::fs::copy(
        files.path().join("allowed.jsonl"),
        files.path().join("copy.jsonl"),
    )
    .unwrap();
    assert!(
        Discovery::new(&args(files.path()))
            .list(&browser)
            .is_empty()
    );
}

#[tokio::test]
async fn concurrent_store_adoption_and_managed_mapping_deduplication() {
    let root = tempfile::tempdir().unwrap();
    let files = tempfile::tempdir().unwrap();
    let data = tempfile::tempdir().unwrap();
    transcript(&files.path().join("external.jsonl"), root.path(), "race");
    let browser = Arc::new(Browser::new(&[root.path().into()]).unwrap());
    let source = Discovery::new(&args(files.path())).list(&browser).remove(0);
    let store = Arc::new(Store::open(data.path()).unwrap());
    let tasks: Vec<_> = (0..8)
        .map(|_| {
            let source = source.clone();
            let data = data.path().to_owned();
            tokio::task::spawn_blocking(move || {
                Store::open(&data).unwrap().adopt(&source).unwrap().id
            })
        })
        .collect();
    for task in tasks {
        assert_eq!(task.await.unwrap(), source.id);
    }
    assert_eq!(store.v2_sessions().unwrap().len(), 1);
    let directory = SessionDirectory::new(
        store.clone(),
        browser.clone(),
        Arc::new(Bus::default()),
        "missing".into(),
        args(files.path()),
        1,
    );
    let listed = directory.list().await.unwrap();
    assert_eq!(listed.len(), 1);
    assert_eq!(
        listed[0].session.origin,
        pinkcollab_gateway::protocol::SessionOrigin::Managed
    );
    assert_eq!(
        directory
            .history_view(&source.id)
            .await
            .unwrap()
            .unwrap()
            .session
            .engine_session_ref,
        Some(workspace::display(&source.path))
    );
    // Ordinary pre-existing mappings are suppressed as well, without an adoption row.
    let other_data = tempfile::tempdir().unwrap();
    let other = Arc::new(Store::open(other_data.path()).unwrap());
    let mut normal = source.record(other.host_id().unwrap());
    normal.id = "sess_aa".into();
    normal.engine_session_ref = Some(workspace::display(
        &source
            .path
            .parent()
            .unwrap()
            .join(".")
            .join("external.jsonl"),
    ));
    other
        .create_v2("client", "create", "fingerprint", normal)
        .unwrap();
    assert!(other.adopt(&source).is_err());
    let normal_dir = SessionDirectory::new(
        other,
        browser,
        Arc::new(Bus::default()),
        "missing".into(),
        args(files.path()),
        1,
    );
    let listed = normal_dir.list().await.unwrap();
    assert_eq!(listed.len(), 1);
    assert_eq!(listed[0].session.id, "sess_aa");
}

#[tokio::test]
async fn visible_writer_and_removed_workspace_block_adoption() {
    let root = tempfile::tempdir().unwrap();
    let files = tempfile::tempdir().unwrap();
    let data = tempfile::tempdir().unwrap();
    let path = files.path().join("external.jsonl");
    transcript(&path, root.path(), "busy");
    let store = Arc::new(Store::open(data.path()).unwrap());
    let directory = SessionDirectory::new(
        store.clone(),
        Arc::new(Browser::new(&[root.path().into()]).unwrap()),
        Arc::new(Bus::default()),
        "missing".into(),
        args(files.path()),
        1,
    );
    let id = directory.list().await.unwrap()[0].session.id.clone();
    std::fs::write(files.path().join(".external.jsonl.lock"), "busy").unwrap();
    let result = directory
        .submit("c".into(), id.clone(), "busy".into(), Command::StartRuntime)
        .await;
    assert!(matches!(
        result,
        Err(pinkcollab_gateway::runtime::SubmitError::ExternalBusy)
    ));
    assert!(store.v2_sessions().unwrap().is_empty());
    let other = tempfile::tempdir().unwrap();
    let removed = SessionDirectory::new(
        store.clone(),
        Arc::new(Browser::new(&[other.path().into()]).unwrap()),
        Arc::new(Bus::default()),
        "missing".into(),
        args(files.path()),
        1,
    );
    assert!(removed.view(&id).await.unwrap().is_none());
    assert!(
        removed
            .submit("c".into(), id, "removed".into(), Command::StartRuntime)
            .await
            .is_err()
    );
    assert!(store.v2_sessions().unwrap().is_empty());
}

#[tokio::test]
#[ignore = "requires installed OMP; resumes isolated version-3 history without a model prompt"]
async fn real_omp_resumes_discovered_history_without_prompt() {
    let root = tempfile::tempdir().unwrap();
    let files = tempfile::tempdir().unwrap();
    let data = tempfile::tempdir().unwrap();
    let executable = std::env::var("OMP_EXECUTABLE").unwrap_or_else(|_| "omp".into());
    let runtime_args = args(files.path());
    let path = files.path().join("external-smoke.jsonl");
    transcript(&path, root.path(), "external-smoke");
    let reference = workspace::display(&path);
    let store = Arc::new(Store::open(data.path()).unwrap());
    let directory = SessionDirectory::new(
        store.clone(),
        Arc::new(Browser::new(&[root.path().into()]).unwrap()),
        Arc::new(Bus::default()),
        executable,
        runtime_args,
        1,
    );
    let list = directory.list().await.unwrap();
    assert_eq!(list.len(), 1);
    assert_eq!(list[0].session.title, "Existing conversation");
    let id = list[0].session.id.clone();
    assert!(
        directory
            .submit(
                "smoke".into(),
                id.clone(),
                "resume".into(),
                Command::StartRuntime
            )
            .await
            .is_ok()
    );
    let receipt = tokio::time::timeout(std::time::Duration::from_secs(30), async {
        loop {
            let receipt = directory
                .operation("smoke", &id, "resume")
                .await
                .unwrap()
                .unwrap();
            if !["accepted", "dispatching", "running"].contains(&receipt.status.as_str()) {
                break receipt;
            }
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        }
    })
    .await
    .unwrap();
    directory.close().await;
    assert_eq!(receipt.status, "succeeded", "{:?}", receipt.error);
    assert_eq!(
        Path::new(
            store
                .v2_session(&id)
                .unwrap()
                .unwrap()
                .engine_session_ref
                .as_ref()
                .unwrap()
        )
        .canonicalize()
        .unwrap(),
        Path::new(&reference).canonicalize().unwrap()
    );
    assert_eq!(directory.list().await.unwrap().len(), 1);
}

#[tokio::test]
async fn replacement_during_startup_must_not_receive_prompt() {
    let cwd = tempfile::tempdir().unwrap();
    let files = tempfile::tempdir().unwrap();
    let data = tempfile::tempdir().unwrap();
    let path = files.path().join("history.jsonl");
    let header = |id: &str| {
        format!(
            "{}\n",
            json!({"type":"session","version":3,"id":id,"cwd":workspace::display(&cwd.path().canonicalize().unwrap()),"timestamp":"2026-01-01T00:00:00Z"})
        )
    };
    std::fs::write(&path, header("original")).unwrap();
    let directory = SessionDirectory::new(
        Arc::new(Store::open(data.path()).unwrap()),
        Arc::new(Browser::new(&[cwd.path().into()]).unwrap()),
        Arc::new(Bus::default()),
        env!("CARGO_BIN_EXE_omp-fixture").into(),
        vec![
            "--session-dir".into(),
            workspace::display(files.path()),
            "--spawn-child".into(),
        ],
        1,
    );
    let id = directory.list().await.unwrap()[0].session.id.clone();
    directory
        .submit(
            "review".into(),
            id.clone(),
            "prompt".into(),
            Command::Prompt {
                generation: None,
                message: "review_prompt".into(),
                file_ids: vec![],
            },
        )
        .await
        .ok()
        .unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        loop {
            if directory
                .view(&id)
                .await
                .unwrap()
                .unwrap()
                .runtime
                .is_some()
            {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    // Startup has validated the old identity, but the subprocess has not emitted ready.
    std::fs::write(&path, header("replacement")).unwrap();
    std::fs::write(cwd.path().join("job-ready"), "ready").unwrap();
    let receipt = tokio::time::timeout(std::time::Duration::from_secs(5), async {
        loop {
            let receipt = directory
                .operation("review", &id, "prompt")
                .await
                .unwrap()
                .unwrap();
            if !["accepted", "dispatching", "running"].contains(&receipt.status.as_str()) {
                break receipt;
            }
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    directory.close().await;
    assert_eq!(receipt.status, "failed");
    assert_eq!(
        receipt.error.as_ref().unwrap()["code"],
        "history_unavailable"
    );
    let content = std::fs::read_to_string(&path).unwrap();
    assert!(
        !content.contains("review_prompt"),
        "prompt reached replacement transcript; receipt={}",
        receipt.status
    );
}
