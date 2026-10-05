use chrono::{TimeZone, Utc};
use pinkcollab_gateway::{
    history::{self, FeedbackRecord, HistorySync},
    model::TimelineItem,
};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::path::Path;

// These scenarios share the process-wide, nonblocking history-reader limit.
static HISTORY_READS: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

fn record(path: &Path, anchor: &str, id: &str, text: &str, timestamp: i64) -> FeedbackRecord {
    FeedbackRecord {
        reference: path.to_string_lossy().into_owned(),
        anchor: anchor.into(),
        sequence: timestamp as u64,
        item: TimelineItem {
            id: id.into(),
            source_id: Some(id.into()),
            message_key: None,
            kind: "feedback".into(),
            text: text.into(),
            detail: format!("Question for {id}?"),
            tool: None,
            timestamp: Utc.timestamp_opt(timestamp, 0).single().unwrap(),
        },
    }
}

fn write(path: &Path, entries: &[Value]) {
    std::fs::write(
        path,
        entries
            .iter()
            .map(Value::to_string)
            .collect::<Vec<_>>()
            .join("\n"),
    )
    .unwrap();
}

fn ids(page: &Value) -> Vec<&str> {
    page["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["id"].as_str().unwrap())
        .collect()
}

#[tokio::test]
async fn feedback_pages_and_sync_preserve_tool_positions_and_active_branch_identity() {
    let _history_reads = HISTORY_READS.lock().await;
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("history.jsonl");
    let mut entries = vec![
        json!({"id":"root","type":"message","message":{"role":"user","content":"Start"}}),
        json!({"id":"abandoned","parentId":"root","type":"message","message":{"role":"assistant","content":"Abandoned reply"}}),
        json!({"id":"current","parentId":"root","type":"message","message":{"role":"assistant","content":[
            {"type":"text","text":"Current reply"},
            {"type":"toolCall","id":"active-call","name":"bash","arguments":{"command":"check"}}
        ]}}),
        json!({"id":"result","parentId":"current","type":"message","message":{"role":"toolResult",
            "toolCallId":"active-call","toolName":"bash","content":[{"type":"text","text":"done"}]}}),
        json!({"id":"last","parentId":"result","type":"message","message":{"role":"assistant","content":"Final reply"}}),
    ];
    write(&path, &entries);
    let mut feedback = vec![
        record(&path, "abandoned", "feedback-abandoned", "secret", 20),
        record(&path, "current", "feedback-current", "new", 1),
        record(&path, "root", "feedback-root", "Confirmed", 100),
        record(
            &dir.path().join("other.jsonl"),
            "current",
            "wrong-reference",
            "secret",
            2,
        ),
    ];
    let latest = history::history_page_with_anchor(
        history::HistorySource {
            path: &path,
            allow_missing: false,
        },
        "session",
        None,
        2,
        None,
        None,
        &feedback,
    )
    .await
    .unwrap();
    assert_eq!(ids(&latest), ["feedback-current", "last"]);
    let old_cursor = latest["nextCursor"].as_str().unwrap().to_owned();
    let middle = history::history_page_with_anchor(
        history::HistorySource {
            path: &path,
            allow_missing: false,
        },
        "session",
        Some(&old_cursor),
        2,
        None,
        None,
        &feedback,
    )
    .await
    .unwrap();
    assert_eq!(ids(&middle), ["current", "active-call"]);
    assert_eq!(middle["items"][1]["tool"]["completed"], true);
    let oldest = history::history_page_with_anchor(
        history::HistorySource {
            path: &path,
            allow_missing: false,
        },
        "session",
        middle["nextCursor"].as_str(),
        2,
        None,
        None,
        &feedback,
    )
    .await
    .unwrap();
    assert_eq!(ids(&oldest), ["root", "feedback-root"]);
    assert!(oldest["nextCursor"].is_null());
    let synced = history::sync_history(
        history::HistorySource {
            path: &path,
            allow_missing: false,
        },
        "session",
        &HistorySync {
            anchor: Some("current".into()),
            oldest: Some("entry:root".into()),
            cursor: None,
            known: vec![json!({"id":"active-call","sourceId":"tool:active-call","version":"old"})],
        },
        &feedback,
    )
    .await
    .unwrap();
    assert_eq!(ids(&synced), ["active-call", "feedback-current", "last"]);
    assert_eq!(synced["source"]["continues"], true);
    assert!(synced["nextCursor"].is_null());
    // New feedback at the existing leaf must be discoverable even without another JSONL entry.
    feedback.push(record(&path, "last", "feedback-last", "", 3));
    let same_leaf = history::sync_history(
        history::HistorySource {
            path: &path,
            allow_missing: false,
        },
        "session",
        &HistorySync {
            anchor: Some("last".into()),
            oldest: None,
            cursor: None,
            known: vec![],
        },
        &feedback,
    )
    .await
    .unwrap();
    assert_eq!(ids(&same_leaf), ["feedback-last"]);
    assert_eq!(same_leaf["items"][0]["text"], "");
    assert_eq!(same_leaf["source"]["branchLeaf"], "last");
    let confirmed = history::sync_history(
        history::HistorySource {
            path: &path,
            allow_missing: false,
        },
        "session",
        &HistorySync {
            anchor: Some("last".into()),
            oldest: None,
            cursor: None,
            known: vec![json!({"id":"live-feedback","sourceId":"feedback-last",
    "textHash":hex::encode(Sha256::digest(b""))})],
        },
        &feedback,
    )
    .await
    .unwrap();
    assert_eq!(confirmed["items"], json!([]));
    assert_eq!(confirmed["confirmed"][0]["id"], "live-feedback");
    assert_eq!(
        confirmed["confirmed"][0]["item"]["detail"],
        "Question for feedback-last?"
    );
    assert_eq!(confirmed["order"], json!(["feedback-last"]));
    // A normal append keeps the earlier identity boundary, despite feedback-only additions.
    let preceding = history::history_page_with_anchor(
        history::HistorySource {
            path: &path,
            allow_missing: false,
        },
        "session",
        Some(&old_cursor),
        2,
        None,
        None,
        &feedback,
    )
    .await
    .unwrap();
    assert_eq!(ids(&preceding), ["current", "active-call"]);
    entries.push(json!({"id":"new-branch","parentId":"root","type":"message","message":{"role":"user","content":"Switch branch"}}));
    write(&path, &entries);
    assert_eq!(
        history::history_page_with_anchor(
            history::HistorySource {
                path: &path,
                allow_missing: false
            },
            "session",
            Some(&old_cursor),
            2,
            None,
            None,
            &feedback,
        )
        .await
        .unwrap_err()
        .to_string(),
        "stale_cursor"
    );
    let switched = history::sync_history(
        history::HistorySource {
            path: &path,
            allow_missing: false,
        },
        "session",
        &HistorySync {
            anchor: Some("last".into()),
            oldest: None,
            cursor: None,
            known: vec![],
        },
        &feedback,
    )
    .await
    .unwrap();
    assert_eq!(ids(&switched), ["root", "feedback-root", "new-branch"]);
    assert_eq!(switched["source"]["continues"], false);
}

#[tokio::test]
async fn forward_sync_cursor_rejects_feedback_changes_with_an_unchanged_transcript() {
    let _history_reads = HISTORY_READS.lock().await;
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("history.jsonl");
    write(&path, &[json!({"id":"root","type":"session"})]);
    let text = "🙂".repeat(65536);
    let mut feedback: Vec<_> = (0..18)
        .map(|index| record(&path, "root", &format!("feedback-{index:02}"), &text, index))
        .collect();
    let first = history::sync_history(
        history::HistorySource {
            path: &path,
            allow_missing: false,
        },
        "session",
        &HistorySync {
            anchor: Some("root".into()),
            oldest: None,
            cursor: None,
            known: vec![],
        },
        &feedback,
    )
    .await
    .unwrap();
    let cursor = first["syncCursor"].as_str().unwrap().to_owned();
    let next = history::sync_history(
        history::HistorySource {
            path: &path,
            allow_missing: false,
        },
        "session",
        &HistorySync {
            anchor: Some("root".into()),
            oldest: None,
            cursor: Some(cursor.clone()),
            known: vec![],
        },
        &feedback,
    )
    .await
    .unwrap();
    let first_ids = ids(&first);
    let next_ids = ids(&next);
    assert_eq!(first_ids.len() + next_ids.len(), 18);
    assert!(first_ids.iter().all(|id| !next_ids.contains(id)));
    feedback.push(record(&path, "root", "feedback-extra", "new", 19));
    assert_eq!(
        history::sync_history(
            history::HistorySource {
                path: &path,
                allow_missing: false
            },
            "session",
            &HistorySync {
                anchor: Some("root".into()),
                oldest: None,
                cursor: Some(cursor),
                known: vec![],
            },
            &feedback,
        )
        .await
        .unwrap_err()
        .to_string(),
        "stale_cursor"
    );
}

#[tokio::test]
async fn saved_responses_outlive_receipt_windows_and_sqlite_reopen() {
    let _history_reads = HISTORY_READS.lock().await;
    use pinkcollab_gateway::{
        domain::{OperationRecord, SessionRecord},
        storage::Store,
    };
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("history.jsonl");
    write(&path, &[json!({"id":"root","type":"session"})]);
    let reference = path.to_string_lossy().into_owned();
    let data = dir.path().join("data");
    let store = Store::open(&data).unwrap();
    let now = Utc::now();
    store
        .create_v2(
            "client",
            "create",
            "fingerprint",
            SessionRecord {
                id: "session".into(),
                host_id: store.host_id().unwrap(),
                cwd: dir.path().to_string_lossy().into_owned(),
                title: "Feedback only".into(),
                metadata_revision: 0,
                created_at: now,
                updated_at: now,
                archived_at: None,
                engine_session_ref: Some(reference.clone()),
            },
        )
        .unwrap();
    for index in 0..120 {
        let timestamp = Utc.timestamp_opt(1000 + index, 0).single().unwrap();
        let feedback = record(
            &path,
            "root",
            &format!("feedback-{index:03}"),
            "new",
            1000 + index,
        );
        let op = OperationRecord {
            command_id: format!("answer-{index:03}"),
            client_id: "client".into(),
            session_id: "session".into(),
            command_type: "respond".into(),
            request_fingerprint: format!("fingerprint-{index}"),
            runtime_generation: Some("old-runtime".into()),
            status: "succeeded".into(),
            result: Some(json!({"inputRequestId":format!("question-{index}"),"feedback":feedback})),
            error: None,
            created_at: timestamp,
            updated_at: timestamp,
        };
        assert!(store.insert_operation(&op).unwrap());
    }
    let mut rejected = store
        .operation("client", "session", "answer-119")
        .unwrap()
        .unwrap();
    rejected.command_id = "rejected".into();
    rejected.status = "failed".into();
    assert!(store.insert_operation(&rejected).unwrap());
    assert_eq!(store.recent_operations("session", 20).unwrap().len(), 20);
    assert_eq!(
        store.feedback_records("session", &reference).unwrap().len(),
        120
    );
    assert!(
        store
            .feedback_records("another-session", &reference)
            .unwrap()
            .is_empty()
    );
    assert!(
        store
            .feedback_records("session", "different-reference")
            .unwrap()
            .is_empty()
    );
    drop(store);
    let reopened = Store::open(&data).unwrap();
    reopened.recover_operations().unwrap();
    let records = reopened.feedback_records("session", &reference).unwrap();
    assert_eq!(records.len(), 120);
    assert_eq!(records[0].item.id, "feedback-000");
    assert_eq!(records[119].item.id, "feedback-119");
    let latest = history::history_page_with_anchor(
        history::HistorySource {
            path: &path,
            allow_missing: false,
        },
        "session",
        None,
        100,
        None,
        None,
        &records,
    )
    .await
    .unwrap();
    assert_eq!(latest["items"].as_array().unwrap().len(), 100);
    assert_eq!(latest["items"][0]["id"], "feedback-020");
    let older = history::history_page_with_anchor(
        history::HistorySource {
            path: &path,
            allow_missing: false,
        },
        "session",
        latest["nextCursor"].as_str(),
        100,
        None,
        None,
        &records,
    )
    .await
    .unwrap();
    assert_eq!(older["items"].as_array().unwrap().len(), 20);
    assert_eq!(older["items"][19]["id"], "feedback-019");
    assert!(older["nextCursor"].is_null());
}

#[tokio::test]
async fn feedback_at_one_anchor_follows_delivery_order_even_if_wall_clock_moves_backwards() {
    let _history_reads = HISTORY_READS.lock().await;
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("history.jsonl");
    write(
        &path,
        &[
            json!({"id":"root","type":"message","message":{"role":"user","content":"Start"}}),
            json!({"id":"reply","parentId":"root","type":"message","message":{"role":"assistant","content":"Reply"}}),
        ],
    );
    let mut first = record(&path, "root", "first-response", "Confirmed", 100);
    first.sequence = 1;
    let mut second = record(&path, "root", "second-response", "Declined", 1);
    second.sequence = 2;
    let feedback = [second, first];
    let page = history::history_page_with_anchor(
        history::HistorySource {
            path: &path,
            allow_missing: false,
        },
        "session",
        None,
        50,
        None,
        None,
        &feedback,
    )
    .await
    .unwrap();
    assert_eq!(
        ids(&page),
        ["root", "first-response", "second-response", "reply"]
    );
    assert!(
        page["items"][1]["timestamp"].as_str().unwrap()
            > page["items"][2]["timestamp"].as_str().unwrap()
    );
}

#[tokio::test]
async fn explicitly_missing_transcript_merges_root_feedback_for_pages_and_sync() {
    let _history_reads = HISTORY_READS.lock().await;
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("not-created.jsonl");
    let feedback = [record(&path, "", "root-feedback", "answer", 1)];
    let page = history::history_page_with_anchor(
        history::HistorySource {
            path: &path,
            allow_missing: true,
        },
        "session",
        None,
        50,
        None,
        None,
        &feedback,
    )
    .await
    .unwrap();
    assert_eq!(ids(&page), ["root-feedback"]);
    assert_eq!(page["source"]["branchLeaf"], "");
    assert_eq!(page["source"]["byteLength"], 0);
    let synced = history::sync_history(
        history::HistorySource {
            path: &path,
            allow_missing: true,
        },
        "session",
        &HistorySync {
            anchor: Some(String::new()),
            oldest: None,
            known: vec![],
            cursor: None,
        },
        &feedback,
    )
    .await
    .unwrap();
    assert_eq!(ids(&synced), ["root-feedback"]);
    assert_eq!(synced["source"]["continues"], true);
    assert!(
        history::history_page_with_anchor(
            history::HistorySource {
                path: &path,
                allow_missing: false
            },
            "session",
            None,
            50,
            None,
            None,
            &feedback
        )
        .await
        .is_err()
    );
    assert!(!path.exists());
    std::fs::write(&path, "not JSON\n").unwrap();
    assert!(
        history::history_page_with_anchor(
            history::HistorySource {
                path: &path,
                allow_missing: true
            },
            "session",
            None,
            50,
            None,
            None,
            &feedback
        )
        .await
        .is_err()
    );
}
