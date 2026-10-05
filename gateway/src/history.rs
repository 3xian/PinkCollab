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

/// Gateway-owned input delivery, stored in the command receipt rather than OMP's JSONL.
#[derive(Clone, serde::Serialize, serde::Deserialize)]
pub struct FeedbackRecord {
    pub reference: String,
    pub anchor: String,
    pub sequence: u64,
    pub item: TimelineItem,
}

/// A mapped transcript and its persisted missing-file policy.
#[derive(Clone, Copy)]
pub struct HistorySource<'a> {
    pub path: &'a Path,
    pub allow_missing: bool,
}

/// Missing transcripts are valid only when the caller has checked the persisted mapping policy.
async fn transcript_metadata(
    path: &Path,
    allow_missing: bool,
) -> Result<Option<std::fs::Metadata>> {
    match tokio::fs::metadata(path).await {
        Ok(metadata) => {
            ensure!(metadata.len() <= 128 * 1024 * 1024, "history_unavailable");
            Ok(Some(metadata))
        }
        Err(error) if allow_missing && error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}

async fn transcript_file(path: &Path, allow_missing: bool) -> Result<Option<tokio::fs::File>> {
    match tokio::fs::File::open(path).await {
        Ok(file) => Ok(Some(file)),
        Err(error) if allow_missing && error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}

fn transcript_unchanged(
    before: &Option<std::fs::Metadata>,
    after: &Option<std::fs::Metadata>,
) -> Result<bool> {
    Ok(match (before, after) {
        (Some(before), Some(after)) => {
            before.len() == after.len() && before.modified()? == after.modified()?
        }
        (None, None) => true,
        _ => false,
    })
}

fn transcript_length(metadata: &Option<std::fs::Metadata>) -> u64 {
    metadata.as_ref().map_or(0, std::fs::Metadata::len)
}

/// Capture the insertion boundary before writing a response. Only entry identities are
/// needed here; reconstructing tools/messages would do unnecessary work on the control path.
pub(crate) async fn response_anchor(source: HistorySource<'_>) -> Result<(String, bool)> {
    let HistorySource {
        path,
        allow_missing,
    } = source;
    use futures_util::StreamExt;
    use tokio_util::codec::{FramedRead, LinesCodec};
    #[derive(serde::Deserialize)]
    struct EntryId {
        #[serde(default)]
        id: Option<String>,
    }
    let before = transcript_metadata(path, allow_missing)
        .await
        .context("history_unavailable")?;
    let file = transcript_file(path, allow_missing).await?;
    let mut lines =
        file.map(|file| FramedRead::new(file, LinesCodec::new_with_max_length(16 * 1024 * 1024)));
    let mut leaf = String::new();
    while let Some(line) = match lines.as_mut() {
        Some(lines) => lines.next().await,
        None => None,
    } {
        let entry: EntryId = serde_json::from_str(&line?)?;
        if let Some(id) = entry.id.filter(|id| !id.is_empty()) {
            leaf = id;
        }
    }
    let after = transcript_metadata(path, allow_missing).await?;
    ensure!(
        transcript_unchanged(&before, &after)?,
        "history_unavailable: transcript changed before response"
    );
    Ok((leaf, after.is_some()))
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

pub async fn sync_history(
    source: HistorySource<'_>,
    session_id: &str,
    request: &HistorySync,
    feedback: &[FeedbackRecord],
) -> Result<Value> {
    let HistorySource {
        path,
        allow_missing,
    } = source;
    ensure!(request.known.len() <= 512, "invalid_sync");
    let permit = HISTORY_READERS
        .try_acquire()
        .context("history_unavailable")?;
    let before = transcript_metadata(path, allow_missing).await?;
    let (timeline, leaf, revision, ancestors, positions) =
        build_history(path, true, feedback, allow_missing).await?;
    let after = transcript_metadata(path, allow_missing).await?;
    ensure!(transcript_unchanged(&before, &after)?, "stale_cursor");
    let Some(anchor) = request
        .anchor
        .as_deref()
        .filter(|anchor| ancestors.contains(*anchor))
    else {
        drop(permit);
        return history_page_with_anchor(source, session_id, None, 10, None, None, feedback).await;
    };
    let mut start = *positions.get(anchor).context("stale_cursor")?;
    if let Some(cursor) = &request.cursor {
        let decoded: Value = serde_json::from_slice(&hex::decode(cursor).context("stale_cursor")?)
            .context("stale_cursor")?;
        ensure!(
            decoded["leaf"] == leaf
                && decoded["sessionId"] == session_id
                && decoded["anchor"] == anchor
                && decoded["revision"] == revision,
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
    let sync_cursor = (next < timeline.len()).then(|| hex::encode(serde_json::to_vec(&serde_json::json!({"leaf":leaf,"sessionId":session_id,"anchor":anchor,"index":next,"revision":revision})).unwrap()));
    let oldest = request.oldest.as_deref().and_then(|oldest| {
        timeline
            .iter()
            .position(|item| identity(item) == oldest || item.id == oldest)
    });
    let next_cursor = oldest.filter(|index| *index > 0).map(|index| hex::encode(serde_json::to_vec(&serde_json::json!({"sessionId":session_id,"branchLeaf":leaf,"before":identity(&timeline[index])})).unwrap()));
    let source = hex::encode(Sha256::digest(format!("{session_id}:{leaf}").as_bytes()));
    Ok(
        serde_json::json!({"items":updates,"confirmed":confirmed,"order":order,"source":{"id":source,"branchLeaf":if sync_cursor.is_some(){anchor}else{&leaf},"continues":true,"byteLength":transcript_length(&after)},"nextCursor":next_cursor,"syncCursor":sync_cursor}),
    )
}
pub async fn tool_detail(path: &Path, call_id: &str) -> Result<Option<crate::model::ToolTrace>> {
    let _permit = HISTORY_READERS
        .try_acquire()
        .context("history_unavailable")?;
    let before = tokio::fs::metadata(path).await?;
    ensure!(before.len() <= 128 * 1024 * 1024, "history_unavailable");
    let (items, _, _, _, _) = build_history(path, true, &[], false).await?;
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
    let (mut timeline, _, _, _, _) = build_history(path, false, &[], false).await?;
    trim_timeline(&mut timeline, TIMELINE_LIMIT);
    Ok(timeline)
}

pub async fn history_page(
    path: &Path,
    session_id: &str,
    cursor: Option<&str>,
    limit: usize,
) -> Result<Value> {
    history_page_with_anchor(
        HistorySource {
            path,
            allow_missing: false,
        },
        session_id,
        cursor,
        limit,
        None,
        None,
        &[],
    )
    .await
}

pub async fn history_page_with_anchor(
    source: HistorySource<'_>,
    session_id: &str,
    cursor: Option<&str>,
    limit: usize,
    anchor: Option<&str>,
    oldest: Option<&str>,
    feedback: &[FeedbackRecord],
) -> Result<Value> {
    let HistorySource {
        path,
        allow_missing,
    } = source;
    ensure!((1..=100).contains(&limit), "invalid_page_limit");
    let _permit = HISTORY_READERS
        .try_acquire()
        .context("history_unavailable: history readers busy")?;
    let before = transcript_metadata(path, allow_missing)
        .await
        .context("history_unavailable")?;
    let (timeline, leaf, _revision, ancestors, _) =
        build_history(path, true, feedback, allow_missing)
            .await
            .context("history_unavailable")?;
    let after = transcript_metadata(path, allow_missing)
        .await
        .context("history_unavailable")?;
    ensure!(transcript_unchanged(&before, &after)?, "stale_cursor");
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
    let source = (after.is_some() || !timeline.is_empty()).then(|| {
        serde_json::json!({
            "id":hex::encode(Sha256::digest(format!("{session_id}:{leaf}").as_bytes())),
            "branchLeaf":leaf,"byteLength":transcript_length(&after),"continues":continues,
        })
    });
    let page = serde_json::json!({"items":items,"source":source,"nextCursor":next_cursor});
    ensure!(
        serde_json::to_vec(&page)?.len() <= 8 * 1024 * 1024,
        "history_unavailable: page exceeds response budget; request a smaller limit"
    );
    Ok(page)
}

async fn build_history(
    path: &Path,
    strict: bool,
    feedback: &[FeedbackRecord],
    allow_missing: bool,
) -> Result<(
    Vec<TimelineItem>,
    String,
    String,
    std::collections::HashSet<String>,
    HashMap<String, usize>,
)> {
    use futures_util::StreamExt;
    use tokio_util::codec::{FramedRead, LinesCodec};
    let file = transcript_file(path, allow_missing).await?;
    let mut lines =
        file.map(|file| FramedRead::new(file, LinesCodec::new_with_max_length(16 * 1024 * 1024)));
    let mut digest = Sha256::new();
    let mut entries = HashMap::<String, Value>::new();
    let mut leaf = String::new();
    while let Some(line) = match lines.as_mut() {
        Some(lines) => lines.next().await,
        None => None,
    } {
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
    let mut deliveries: HashMap<&str, Vec<&FeedbackRecord>> = HashMap::new();
    for record in feedback
        .iter()
        .filter(|record| Path::new(&record.reference) == path)
    {
        deliveries.entry(&record.anchor).or_default().push(record);
    }
    for items in deliveries.values_mut() {
        items.sort_unstable_by(|a, b| {
            a.sequence
                .cmp(&b.sequence)
                .then_with(|| a.item.id.cmp(&b.item.id))
        });
    }
    // A response before the first OMP entry belongs to the common root.
    visited.insert(String::new());
    positions.insert(String::new(), 0);
    append_feedback(&mut timeline, deliveries.remove(""), &mut digest);
    for entry in chain {
        append_entry(&mut timeline, entry);
        let id = omp::string(entry, "id");
        // Sync from a leaf must include feedback added there without another OMP entry.
        positions.insert(id.into(), timeline.len());
        append_feedback(&mut timeline, deliveries.remove(id), &mut digest);
    }
    Ok((
        timeline,
        branch_leaf,
        hex::encode(digest.finalize()),
        visited,
        positions,
    ))
}

fn append_feedback(
    timeline: &mut Vec<TimelineItem>,
    items: Option<Vec<&FeedbackRecord>>,
    digest: &mut Sha256,
) {
    if let Some(items) = items {
        for record in items {
            digest.update(record.item.id.as_bytes());
            digest.update(b"\n");
            timeline.push(record.item.clone());
        }
    }
}

fn append_entry(timeline: &mut Vec<TimelineItem>, entry: &Value) {
    if entry["type"] != "message" {
        return;
    }
    let m = &entry["message"];
    let role = omp::string(m, "role");
    let timestamp = omp::string(entry, "timestamp")
        .parse()
        .unwrap_or_else(|_| Utc::now());
    if role == "toolResult" || role == "tool" {
        let call_id = omp::string(m, "toolCallId");
        if call_id.is_empty() {
            return;
        }
        let result = omp::text_content(m);
        upsert_tool(
            timeline,
            TimelineItem::tool_completed(
                call_id,
                omp::string(m, "toolName"),
                result,
                m["isError"] == true,
                timestamp,
            )
            .with_todo_details(&m["details"]),
        );
        return;
    }
    if !["user", "assistant"].contains(&role) {
        return;
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
                    timeline,
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
        return;
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

/// Upserts a tool item by call id. A transcript can hold the result of a call in a different entry
/// than the call itself (or in the other order), and both frames describe one call.
fn upsert_tool(timeline: &mut Vec<TimelineItem>, item: TimelineItem) {
    match timeline.iter_mut().find(|existing| existing.id == item.id) {
        Some(existing) => existing.merge_tool_update(item),
        None => timeline.push(item),
    }
}

fn is_visible_timeline_boundary(item: &TimelineItem) -> bool {
    matches!(
        item.kind.as_str(),
        "user" | "assistant" | "error" | "feedback"
    )
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blank_feedback_is_retained_as_a_visible_boundary_before_hidden_tools() {
        let feedback = TimelineItem {
            id: "feedback".into(),
            source_id: Some("feedback".into()),
            message_key: None,
            kind: "feedback".into(),
            text: String::new(),
            detail: "What should I write?".into(),
            tool: None,
            timestamp: Utc::now(),
        };
        let mut timeline = vec![feedback];
        for index in 0..TIMELINE_LIMIT {
            timeline.push(TimelineItem::tool_started(
                format!("tool-{index}"),
                "bash",
                serde_json::json!({}),
                Utc::now(),
            ));
        }
        trim_timeline(&mut timeline, TIMELINE_LIMIT);
        assert_eq!(timeline.len(), TIMELINE_LIMIT);
        assert_eq!(timeline[0].kind, "feedback");
        assert!(timeline.iter().all(|item| item.id != "tool-0"));
    }
}
