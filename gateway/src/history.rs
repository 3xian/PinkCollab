use crate::{model::TimelineItem, omp};
use anyhow::{Context, Result, ensure};
use chrono::Utc;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{collections::HashMap, path::Path};
use tokio::sync::Semaphore;

/// The live timeline and reconstructed history use the same retention policy.
pub(crate) const TIMELINE_LIMIT: usize = 500;
static HISTORY_READERS: Semaphore = Semaphore::const_new(2);
pub async fn history(path: &Path) -> Result<Vec<TimelineItem>> {
    let (mut timeline, _, _) = build_history(path, false).await?;
    trim_timeline(&mut timeline, TIMELINE_LIMIT);
    Ok(timeline)
}

pub async fn history_page(
    path: &Path,
    session_id: &str,
    cursor: Option<&str>,
    limit: usize,
) -> Result<Value> {
    ensure!((1..=100).contains(&limit), "invalid_page_limit");
    let _permit = HISTORY_READERS
        .try_acquire()
        .context("history_unavailable: history readers busy")?;
    let before = tokio::fs::metadata(path)
        .await
        .context("history_unavailable")?;
    ensure!(
        before.len() <= 128 * 1024 * 1024,
        "history_unavailable: file exceeds read budget"
    );
    let (timeline, leaf, content_hash) = build_history(path, true)
        .await
        .context("history_unavailable")?;
    let after = tokio::fs::metadata(path)
        .await
        .context("history_unavailable")?;
    ensure!(
        before.len() == after.len() && before.modified()? == after.modified()?,
        "stale_cursor"
    );
    let source = hex::encode(Sha256::digest(
        format!("{session_id}:{leaf}:{content_hash}").as_bytes(),
    ));
    let end = match cursor {
        Some(cursor) => {
            let (fingerprint, index) = cursor.split_once(':').context("stale_cursor")?;
            ensure!(fingerprint == source, "stale_cursor");
            index.parse::<usize>().context("stale_cursor")?
        }
        None => timeline.len(),
    };
    ensure!(end <= timeline.len(), "stale_cursor");
    let start = end.saturating_sub(limit);
    let next_cursor = (start > 0).then(|| format!("{source}:{start}"));
    let page = serde_json::json!({"items":&timeline[start..end],"source":{"id":source,"branchLeaf":leaf,"byteLength":after.len()},"nextCursor":next_cursor});
    ensure!(
        serde_json::to_vec(&page)?.len() <= 8 * 1024 * 1024,
        "history_unavailable: page exceeds response budget; request a smaller limit"
    );
    Ok(page)
}

async fn build_history(path: &Path, strict: bool) -> Result<(Vec<TimelineItem>, String, String)> {
    use futures_util::StreamExt;
    use tokio_util::codec::{FramedRead, LinesCodec};
    let file = tokio::fs::File::open(path).await?;
    let mut lines = FramedRead::new(file, LinesCodec::new_with_max_length(16 * 1024 * 1024));
    let mut digest = Sha256::new();
    let mut entries = HashMap::<String, Value>::new();
    let mut leaf = String::new();
    while let Some(line) = lines.next().await {
        let line = line?;
        digest.update(line.as_bytes());
        digest.update(b"\n");
        let entry = match serde_json::from_str::<Value>(&line) {
            Ok(entry) => entry,
            Err(err) if strict => return Err(err.into()),
            Err(_) => continue,
        };
        let id = omp::string(&entry, "id");
        if !id.is_empty() {
            leaf = id.into();
            entries.insert(id.into(), entry);
        }
    }
    let branch_leaf = leaf.clone();
    let mut chain = vec![];
    let mut visited = std::collections::HashSet::new();
    while !leaf.is_empty() {
        ensure!(
            !strict || visited.insert(leaf.clone()),
            "history_unavailable: branch cycle"
        );
        if !strict && !visited.insert(leaf.clone()) {
            break;
        }
        let Some(entry) = entries.get(&leaf) else {
            ensure!(!strict, "history_unavailable: missing branch parent");
            break;
        };
        chain.push(entry);
        leaf = omp::string(entry, "parentId").into();
    }
    chain.reverse();
    let mut timeline: Vec<TimelineItem> = vec![];
    for entry in chain {
        if entry["type"] != "message" {
            continue;
        }
        let m = &entry["message"];
        let role = omp::string(m, "role");
        let timestamp = omp::string(entry, "timestamp")
            .parse()
            .unwrap_or_else(|_| Utc::now());
        if role == "toolResult" || role == "tool" {
            let call_id = omp::string(m, "toolCallId");
            if call_id.is_empty() {
                continue;
            }
            let result = omp::text_content(m);
            upsert_tool(
                &mut timeline,
                TimelineItem::tool_completed(
                    call_id,
                    omp::string(m, "toolName"),
                    result,
                    m["isError"] == true,
                    timestamp,
                ),
            );
            continue;
        }
        if !["user", "assistant"].contains(&role) {
            continue;
        }
        let entry_id = omp::string(entry, "id");
        if role == "assistant"
            && let Some(parts) = m["content"].as_array()
        {
            for (index, part) in parts.iter().enumerate() {
                match omp::string(part, "type") {
                    "text" => {
                        let text = omp::string(part, "text").trim();
                        if !text.is_empty() {
                            timeline.push(TimelineItem {
                                id: format!("{entry_id}:{index}"),
                                kind: "assistant".into(),
                                text: text.into(),
                                detail: String::new(),
                                tool: None,
                                timestamp,
                            });
                        }
                    }
                    "toolCall" => {
                        let call_id = omp::string(part, "id");
                        let name = omp::string(part, "name");
                        if call_id.is_empty() || name.is_empty() {
                            continue;
                        }
                        let arguments = part["arguments"].clone();
                        upsert_tool(
                            &mut timeline,
                            TimelineItem::tool_started(call_id, name, arguments, timestamp),
                        );
                    }
                    _ => {}
                }
            }
            if omp::string(m, "stopReason") == "error" {
                timeline.push(TimelineItem {
                    id: format!("{entry_id}:error"),
                    kind: "error".into(),
                    text: omp::string(m, "errorMessage").into(),
                    detail: String::new(),
                    tool: None,
                    timestamp,
                });
            }
            continue;
        }
        let text = m["content"]
            .as_str()
            .map(str::to_owned)
            .unwrap_or_else(|| omp::text_content(m));
        if !text.is_empty() {
            timeline.push(TimelineItem {
                id: entry_id.into(),
                kind: role.into(),
                text,
                detail: String::new(),
                tool: None,
                timestamp,
            });
        }
    }
    Ok((timeline, branch_leaf, hex::encode(digest.finalize())))
}

/// Upserts a tool item by call id. A transcript can hold the result of a call in a different entry
/// than the call itself (or in the other order), and both frames describe one call.
fn upsert_tool(timeline: &mut Vec<TimelineItem>, item: TimelineItem) {
    match timeline.iter_mut().find(|existing| existing.id == item.id) {
        Some(existing) => existing.merge_tool_update(item),
        None => timeline.push(item),
    }
}

fn is_visible_timeline_boundary(item: &TimelineItem) -> bool {
    matches!(item.kind.as_str(), "user" | "assistant" | "error")
}

/// Drops the oldest hidden bookkeeping before the oldest visible message, so a burst of tool calls
/// cannot push the conversation itself out of the timeline. Shared by the live timeline and by
/// reconstructed history, which are documented as using the same retention policy.
pub(crate) fn trim_timeline(timeline: &mut Vec<TimelineItem>, limit: usize) {
    while timeline.len() > limit {
        let index = timeline
            .iter()
            .position(|item| !is_visible_timeline_boundary(item))
            .unwrap_or(0);
        timeline.remove(index);
    }
}
