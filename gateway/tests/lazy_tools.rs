use chrono::Utc;
use pinkcollab_gateway::{
    history,
    model::TimelineItem,
    tool_details::{detail_page, message_source},
};
use serde_json::{Value, json};

#[tokio::test]
async fn long_history_pages_reduce_item_count_without_cutting_messages() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("long.jsonl");
    let text = "完整段落🙂".repeat(40_000);
    let entries: Vec<_> = (0..12)
        .map(|index| {
            json!({
                "id":format!("entry-{index}"),
                "parentId":(index > 0).then(|| format!("entry-{}", index - 1)),
                "type":"message",
                "message":{"role":"assistant","timestamp":1000 + index,"content":text}
            })
        })
        .collect();
    std::fs::write(
        &file,
        entries
            .iter()
            .map(Value::to_string)
            .collect::<Vec<_>>()
            .join("\n"),
    )
    .unwrap();
    let mut cursor = None;
    let mut ids = Vec::new();
    loop {
        let page = history::history_page(&file, "session", cursor.as_deref(), 50)
            .await
            .unwrap();
        assert!(serde_json::to_vec(&page).unwrap().len() < 8 * 1024 * 1024);
        for item in page["items"].as_array().unwrap() {
            assert_eq!(item["text"], text);
            ids.push(item["id"].as_str().unwrap().to_owned());
        }
        cursor = page["nextCursor"].as_str().map(str::to_owned);
        if cursor.is_none() {
            break;
        }
    }
    ids.sort();
    ids.dedup();
    assert_eq!(ids.len(), 12);
}

#[test]
fn summary_omits_large_payload_and_pages_reconstruct_lossless_unicode_details() {
    let arguments = json!({"path":"src/main.rs", "content":"大段文件内容".repeat(12000)});
    let output = "完整输出🙂\n".repeat(20000);
    let mut item = TimelineItem::tool_started("call", "write", arguments.clone(), Utc::now());
    item.merge_tool_update(TimelineItem::tool_completed(
        "call",
        "write",
        &output,
        false,
        Utc::now(),
    ));
    let summary = item.summary();
    let wire = serde_json::to_value(&summary).unwrap();
    assert!(wire["tool"]["arguments"].is_null());
    assert!(wire["tool"]["result"].is_null());
    assert_eq!(wire["tool"]["summary"]["files"][0], "src/main.rs");
    assert!(serde_json::to_vec(&summary).unwrap().len() < 4096);
    let trace = item.tool.unwrap();
    let mut cursor = None;
    let mut reconstructed = String::new();
    loop {
        let page = detail_page(&trace, cursor.as_deref()).unwrap();
        assert_eq!(page["version"], wire["tool"]["detailsVersion"]);
        assert!(page["text"].as_str().unwrap().len() <= 32 * 1024);
        reconstructed.push_str(page["text"].as_str().unwrap());
        cursor = page["nextCursor"].as_str().map(str::to_owned);
        if cursor.is_none() {
            break;
        }
    }
    assert_eq!(
        reconstructed,
        format!(
            "Arguments\n{}\n\nResult\n{output}",
            serde_json::to_string_pretty(&arguments).unwrap()
        )
    );
    let first = detail_page(&trace, None).unwrap();
    let mut changed = trace.clone();
    changed.result.push('x');
    assert!(detail_page(&changed, first["nextCursor"].as_str()).is_ok());
    changed.arguments = json!({"path":"rewritten.rs"});
    assert_eq!(
        detail_page(&changed, first["nextCursor"].as_str())
            .unwrap_err()
            .to_string(),
        "stale_detail"
    );
    assert!(detail_page(&trace, Some("invalid")).is_err());
}

#[tokio::test]
async fn history_summaries_and_details_follow_only_the_active_branch() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("session.jsonl");
    let message = json!({"role":"assistant","timestamp":12345,"content":[
        {"type":"text","text":"first paragraph"}, {"type":"text","text":"second paragraph"},
        {"type":"toolCall","id":"active-call","name":"bash","arguments":{"cmd":"cargo test"}}
    ]});
    let entries = [
        json!({"id":"root","type":"message","message":{"role":"user","content":"start"}}),
        json!({"id":"abandoned","parentId":"root","type":"message","message":{"role":"assistant","content":[{"type":"toolCall","id":"other-call","name":"bash","arguments":{"cmd":"secret"}}]}}),
        json!({"id":"current","parentId":"root","type":"message","message":message}),
        json!({"id":"result","parentId":"current","type":"message","message":{"role":"toolResult","toolCallId":"active-call","toolName":"bash","content":[{"type":"text","text":"passed"}]}}),
    ];
    std::fs::write(
        &file,
        entries
            .iter()
            .map(Value::to_string)
            .collect::<Vec<_>>()
            .join("\n"),
    )
    .unwrap();
    let page = history::history_page(&file, "session", None, 50)
        .await
        .unwrap();
    let assistant = page["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["kind"] == "assistant")
        .unwrap();
    assert_eq!(assistant["text"], "first paragraph\nsecond paragraph");
    assert_eq!(
        assistant["sourceId"],
        message_source(&message, "different-rpc-id")
    );
    let tool = page["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["kind"] == "tool")
        .unwrap();
    assert!(tool["tool"]["arguments"].is_null());
    assert!(tool["tool"]["result"].is_null());
    let trace = history::tool_detail(&file, "active-call")
        .await
        .unwrap()
        .unwrap();
    assert_eq!(trace.arguments["cmd"], "cargo test");
    assert_eq!(trace.result, "passed");
    assert!(
        history::tool_detail(&file, "other-call")
            .await
            .unwrap()
            .is_none()
    );
    let continued = history::history_page_with_anchor(
        history::HistorySource {
            path: &file,
            allow_missing: false,
        },
        "session",
        None,
        1,
        Some("current"),
        Some("entry:root"),
        &[],
    )
    .await
    .unwrap();
    assert_eq!(continued["source"]["continues"], true);
    assert!(continued["nextCursor"].is_null());
    let switched = history::history_page_with_anchor(
        history::HistorySource {
            path: &file,
            allow_missing: false,
        },
        "session",
        None,
        1,
        Some("abandoned"),
        Some("entry:root"),
        &[],
    )
    .await
    .unwrap();
    assert_eq!(switched["source"]["continues"], false);
    assert!(switched["nextCursor"].is_string());
}

#[test]
fn running_tool_cursor_survives_append_and_completion_but_rejects_rewrites() {
    let mut trace = TimelineItem::tool_started("call", "bash", json!({"cmd":"test"}), Utc::now())
        .tool
        .unwrap();
    trace.result = "first🙂".into();
    let initial = detail_page(&trace, None).unwrap();
    let cursor = initial["resumeCursor"].as_str().unwrap();
    assert_eq!(initial["hasMore"], false);
    assert!(initial["nextCursor"].is_string());
    let version = trace.details_version();
    trace.result.push_str("\nsecond🙂");
    assert_eq!(
        trace.details_version(),
        version,
        "progress does not invalidate the summary"
    );
    let appended = detail_page(&trace, Some(cursor)).unwrap();
    assert_eq!(appended["text"], "\nsecond🙂");
    trace.completed = true;
    assert_ne!(trace.details_version(), version);
    let settled = detail_page(&trace, appended["resumeCursor"].as_str()).unwrap();
    assert_eq!(settled["text"], "");
    assert!(settled["nextCursor"].is_null());
    trace.result = "rewritten".into();
    assert_eq!(
        detail_page(&trace, Some(cursor)).unwrap_err().to_string(),
        "stale_detail"
    );
}

#[tokio::test]
async fn history_sync_confirms_cached_text_without_retransmitting_it_and_keeps_older_cursor() {
    use sha2::{Digest, Sha256};
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("history.jsonl");
    let mut entries: Vec<_> = (0..5).map(|i| json!({"id":format!("e{i}"),"parentId":(i>0).then(||format!("e{}",i-1)),"type":"message","message":{"role":"assistant","timestamp":100+i,"content":format!("body-{i}")}})).collect();
    let write = |entries: &[Value]| {
        std::fs::write(
            &file,
            entries
                .iter()
                .map(Value::to_string)
                .collect::<Vec<_>>()
                .join("\n"),
        )
        .unwrap()
    };
    write(&entries);
    let first = history::history_page(&file, "session", None, 2)
        .await
        .unwrap();
    let older = first["nextCursor"].as_str().unwrap();
    entries.push(json!({"id":"e5","parentId":"e4","type":"message","message":{"role":"assistant","timestamp":105,"content":"a large cached final reply"}}));
    write(&entries);
    let saved = history::history_page(&file, "session", None, 1)
        .await
        .unwrap();
    let latest = &saved["items"][0];
    let page = history::sync_history(history::HistorySource { path: &file, allow_missing: false }, "session", &history::HistorySync {
        anchor:Some("e4".into()),oldest:first["items"][0]["sourceId"].as_str().map(str::to_owned),cursor:None,
        known:vec![json!({"id":"live-key","sourceId":latest["sourceId"],"textHash":hex::encode(Sha256::digest(latest["text"].as_str().unwrap().as_bytes()))})],
    }, &[]).await.unwrap();
    assert_eq!(page["items"], json!([]));
    assert_eq!(page["confirmed"][0]["id"], "live-key");
    assert_eq!(page["confirmed"][0]["item"]["text"], "");
    assert_eq!(page["source"]["branchLeaf"], "e5");
    let preceding = history::history_page(&file, "session", Some(older), 2)
        .await
        .unwrap();
    assert_eq!(preceding["items"][0]["text"], "body-1");
    entries.push(json!({"id":"branch","parentId":"e0","type":"message","message":{"role":"user","content":"new branch"}}));
    write(&entries);
    assert_eq!(
        history::history_page(&file, "session", Some(older), 2)
            .await
            .unwrap_err()
            .to_string(),
        "stale_cursor"
    );
    let reset = history::sync_history(
        history::HistorySource {
            path: &file,
            allow_missing: false,
        },
        "session",
        &history::HistorySync {
            anchor: Some("e5".into()),
            oldest: None,
            cursor: None,
            known: vec![],
        },
        &[],
    )
    .await
    .unwrap();
    assert_eq!(reset["source"]["continues"], false);
    assert_eq!(reset["items"].as_array().unwrap().len(), 2);
}
