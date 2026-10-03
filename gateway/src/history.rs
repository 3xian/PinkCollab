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
fn identity(item: &TimelineItem) -> &str {
    item.source_id.as_deref().unwrap_or(&item.id)
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HistorySync {
    pub anchor: Option<String>,
    pub oldest: Option<String>,
    #[serde(default)]
    pub known: Vec<Value>,
    pub cursor: Option<String>,
}

pub async fn sync_history(path: &Path, session_id: &str, request: &HistorySync) -> Result<Value> {
    ensure!(request.known.len() <= 512, "invalid_sync");
    let permit = HISTORY_READERS
        .try_acquire()
        .context("history_unavailable")?;
    let before = tokio::fs::metadata(path).await?;
    ensure!(before.len() <= 128 * 1024 * 1024, "history_unavailable");
    let (timeline, leaf, _, ancestors, positions) = build_history(path, true).await?;
    let after = tokio::fs::metadata(path).await?;
    ensure!(
        before.len() == after.len() && before.modified()? == after.modified()?,
        "stale_cursor"
    );
    let Some(anchor) = request
        .anchor
        .as_deref()
        .filter(|anchor| ancestors.contains(*anchor))
    else {
        drop(permit);
        return history_page_with_anchor(path, session_id, None, 10, None, None).await;
    };
    let mut start = *positions.get(anchor).context("stale_cursor")?;
    if let Some(cursor) = &request.cursor {
        let decoded: Value = serde_json::from_slice(&hex::decode(cursor).context("stale_cursor")?)
            .context("stale_cursor")?;
        ensure!(
            decoded["leaf"] == leaf
                && decoded["sessionId"] == session_id
                && decoded["anchor"] == anchor,
            "stale_cursor"
        );
        start = decoded["index"].as_u64().context("stale_cursor")? as usize;
    }
    ensure!(start <= timeline.len(), "stale_cursor");
    let known: HashMap<_, _> = request
        .known
        .iter()
        .filter_map(|known| known["sourceId"].as_str().map(|source| (source, known)))
        .collect();
    let mut updates = Vec::new();
    let mut confirmed = Vec::new();
    let mut order = Vec::new();
    let mut bytes = 0;
    let mut next = timeline.len();
    for (index, raw) in timeline.iter().enumerate() {
        let previous = known.get(identity(raw)).copied();
        if index < start && (request.cursor.is_some() || previous.is_none() || raw.tool.is_none()) {
            continue;
        }
        let item = raw.summary();
        if let Some(previous) = previous {
            if let Some(tool) = &item.tool {
                if previous["version"] == tool.details_version {
                    continue;
                }
            } else if previous["textHash"] == hex::encode(Sha256::digest(item.text.as_bytes())) {
                if index >= start {
                    let mut metadata = item;
                    metadata.text.clear();
                    order.push(identity(&metadata).to_owned());
                    confirmed.push(serde_json::json!({"id":previous["id"],"textHash":previous["textHash"],"item":metadata}));
                }
                continue;
            }
        }
        let size = serde_json::to_vec(&item)?.len();
        if index >= start && bytes + size > 4 * 1024 * 1024 && !updates.is_empty() {
            next = index;
            break;
        }
        bytes += size;
        order.push(identity(&item).to_owned());
        updates.push(item);
    }
    let sync_cursor = (next < timeline.len()).then(|| hex::encode(serde_json::to_vec(&serde_json::json!({"leaf":leaf,"sessionId":session_id,"anchor":anchor,"index":next})).unwrap()));
    let oldest = request.oldest.as_deref().and_then(|oldest| {
        timeline
            .iter()
            .position(|item| identity(item) == oldest || item.id == oldest)
    });
    let next_cursor = oldest.filter(|index| *index > 0).map(|index| hex::encode(serde_json::to_vec(&serde_json::json!({"sessionId":session_id,"branchLeaf":leaf,"before":identity(&timeline[index])})).unwrap()));
    let source = hex::encode(Sha256::digest(format!("{session_id}:{leaf}").as_bytes()));
    Ok(
        serde_json::json!({"items":updates,"confirmed":confirmed,"order":order,"source":{"id":source,"branchLeaf":if sync_cursor.is_some(){anchor}else{&leaf},"continues":true,"byteLength":after.len()},"nextCursor":next_cursor,"syncCursor":sync_cursor}),
    )
}
pub async fn tool_detail(path: &Path, call_id: &str) -> Result<Option<crate::model::ToolTrace>> {
    let _permit = HISTORY_READERS
        .try_acquire()
        .context("history_unavailable")?;
    let before = tokio::fs::metadata(path).await?;
    ensure!(before.len() <= 128 * 1024 * 1024, "history_unavailable");
    let (items, _, _, _, _) = build_history(path, true).await?;
    let after = tokio::fs::metadata(path).await?;
    ensure!(
        before.len() == after.len() && before.modified()? == after.modified()?,
        "history_unavailable"
    );
    Ok(items
        .into_iter()
        .filter_map(|item| item.tool)
        .find(|tool| tool.call_id == call_id))
}
pub async fn history(path: &Path) -> Result<Vec<TimelineItem>> {
    let (mut timeline, _, _, _, _) = build_history(path, false).await?;
    trim_timeline(&mut timeline, TIMELINE_LIMIT);
    Ok(timeline)
}

pub async fn history_page(
    path: &Path,
    session_id: &str,
    cursor: Option<&str>,
    limit: usize,
) -> Result<Value> {
    history_page_with_anchor(path, session_id, cursor, limit, None, None).await
}

pub async fn history_page_with_anchor(
    path: &Path,
    session_id: &str,
    cursor: Option<&str>,
    limit: usize,
    anchor: Option<&str>,
    oldest: Option<&str>,
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
    let (timeline, leaf, _content_hash, ancestors, _) = build_history(path, true)
        .await
        .context("history_unavailable")?;
    let after = tokio::fs::metadata(path)
        .await
        .context("history_unavailable")?;
    ensure!(
        before.len() == after.len() && before.modified()? == after.modified()?,
        "stale_cursor"
    );
    let source = hex::encode(Sha256::digest(format!("{session_id}:{leaf}").as_bytes()));
    let end = match cursor {
        Some(raw) => {
            let decoded: Value = serde_json::from_slice(&hex::decode(raw).context("stale_cursor")?)
                .context("stale_cursor")?;
            ensure!(decoded["sessionId"] == session_id, "stale_cursor");
            ensure!(
                ancestors.contains(decoded["branchLeaf"].as_str().context("stale_cursor")?),
                "stale_cursor"
            );
            let boundary = decoded["before"].as_str().context("stale_cursor")?;
            timeline
                .iter()
                .position(|item| identity(item) == boundary)
                .context("stale_cursor")?
        }
        None => timeline.len(),
    };
    // Bound transport size by selecting fewer complete messages, never by cutting text.
    let mut items = Vec::new();
    let mut bytes = 0;
    for item in timeline[end.saturating_sub(limit)..end].iter().rev() {
        let item = item.summary();
        let size = serde_json::to_vec(&item)?.len();
        if !items.is_empty() && bytes + size > 4 * 1024 * 1024 {
            break;
        }
        bytes += size;
        items.push(item);
    }
    items.reverse();
    let start = end - items.len();
    let continues = anchor.is_some_and(|anchor| ancestors.contains(anchor));
    let retained_start = if continues {
        oldest.and_then(|oldest| {
            timeline
                .iter()
                .position(|item| item.id == oldest || item.source_id.as_deref() == Some(oldest))
        })
    } else {
        None
    };
    let cursor_start = retained_start.map_or(start, |index| index.min(start));
    let next_cursor = (cursor_start > 0).then(|| hex::encode(serde_json::to_vec(&serde_json::json!({"sessionId":session_id,"branchLeaf":leaf,"before":identity(&timeline[cursor_start])})).unwrap()));
    let page = serde_json::json!({"items":items,"source":{"id":source,"branchLeaf":leaf,"byteLength":after.len(),
        "continues":continues},"nextCursor":next_cursor});
    ensure!(
        serde_json::to_vec(&page)?.len() <= 8 * 1024 * 1024,
        "history_unavailable: page exceeds response budget; request a smaller limit"
    );
    Ok(page)
}

async fn build_history(
    path: &Path,
    strict: bool,
) -> Result<(
    Vec<TimelineItem>,
    String,
    String,
    std::collections::HashSet<String>,
    HashMap<String, usize>,
)> {
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
    let mut positions = HashMap::new();
    let mut previous_entry: Option<String> = None;
    for entry in chain {
        if let Some(previous) = previous_entry.replace(omp::string(entry, "id").into()) {
            positions.insert(previous, timeline.len());
        }
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
                )
                .with_todo_details(&m["details"]),
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
            let text = omp::text_content(m);
            if !text.trim().is_empty() {
                timeline.push(TimelineItem {
                    id: entry_id.into(),
                    source_id: Some(crate::tool_details::message_source(m, entry_id)),
                    message_key: crate::tool_details::message_key(m),
                    kind: "assistant".into(),
                    text,
                    detail: String::new(),
                    tool: None,
                    timestamp,
                });
            }
            for part in parts {
                if omp::string(part, "type") == "toolCall" {
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
            }
            if omp::string(m, "stopReason") == "error" {
                timeline.push(TimelineItem {
                    id: format!("{entry_id}:error"),
                    source_id: None,
                    message_key: None,
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
                source_id: Some(crate::tool_details::message_source(m, entry_id)),
                message_key: crate::tool_details::message_key(m),
                kind: role.into(),
                text,
                detail: String::new(),
                tool: None,
                timestamp,
            });
        }
    }
    if let Some(previous) = previous_entry {
        positions.insert(previous, timeline.len());
    }
    Ok((
        timeline,
        branch_leaf,
        hex::encode(digest.finalize()),
        visited,
        positions,
    ))
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
