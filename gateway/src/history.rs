use crate::{model::TimelineItem, omp};
use anyhow::Result;
use chrono::Utc;
use serde_json::Value;
use std::{
    collections::HashMap,
    io::{self, Write},
    path::{Path, PathBuf},
    sync::Arc,
    time::SystemTime,
};

/// The live timeline and reconstructed history use the same retention policy.
pub(crate) const TIMELINE_LIMIT: usize = 500;
/// Maximum combined serialized size of reconstructed timelines kept for repeated detail reads.
const HISTORY_CACHE_LIMIT: usize = 32 * 1024 * 1024;

#[derive(Clone, Copy, PartialEq, Eq)]
struct HistoryStamp {
    len: u64,
    modified: SystemTime,
}

struct CachedHistory {
    stamp: HistoryStamp,
    timeline: Arc<Vec<TimelineItem>>,
    bytes: usize,
    used: u64,
}

#[derive(Default)]
struct CacheState {
    entries: HashMap<PathBuf, CachedHistory>,
    bytes: usize,
    clock: u64,
}

impl CacheState {
    fn get(&mut self, path: &Path, stamp: HistoryStamp) -> Option<Arc<Vec<TimelineItem>>> {
        let entry = self.entries.get_mut(path)?;
        if entry.stamp != stamp {
            self.remove(path);
            return None;
        }
        self.clock += 1;
        entry.used = self.clock;
        Some(entry.timeline.clone())
    }

    fn remove(&mut self, path: &Path) {
        if let Some(entry) = self.entries.remove(path) {
            self.bytes -= entry.bytes;
        }
    }

    fn insert(
        &mut self,
        path: PathBuf,
        stamp: HistoryStamp,
        timeline: Vec<TimelineItem>,
        bytes: usize,
    ) -> Arc<Vec<TimelineItem>> {
        self.remove(&path);
        let timeline = Arc::new(timeline);
        if bytes <= HISTORY_CACHE_LIMIT {
            while self.bytes + bytes > HISTORY_CACHE_LIMIT {
                let Some(oldest) = self
                    .entries
                    .iter()
                    .min_by_key(|(_, entry)| entry.used)
                    .map(|(path, _)| path.clone())
                else {
                    break;
                };
                self.remove(&oldest);
            }
            self.clock += 1;
            self.bytes += bytes;
            self.entries.insert(
                path,
                CachedHistory {
                    stamp,
                    timeline: timeline.clone(),
                    bytes,
                    used: self.clock,
                },
            );
        }
        timeline
    }
}

#[derive(Default)]
pub(crate) struct HistoryCache {
    state: parking_lot::Mutex<CacheState>,
}

impl HistoryCache {
    pub async fn load(&self, path: &Path) -> Result<Vec<TimelineItem>> {
        let metadata = tokio::fs::metadata(path).await?;
        let stamp = HistoryStamp {
            len: metadata.len(),
            modified: metadata.modified()?,
        };
        if let Some(timeline) = self.state.lock().get(path, stamp) {
            return Ok((*timeline).clone());
        }
        let timeline = history(path).await?;
        let latest = tokio::fs::metadata(path).await?;
        let latest = HistoryStamp {
            len: latest.len(),
            modified: latest.modified()?,
        };
        if latest != stamp {
            return Ok(timeline);
        }
        let mut counter = ByteCounter(0);
        let bytes = serde_json::to_writer(&mut counter, &timeline)
            .map(|()| counter.0)
            .unwrap_or(HISTORY_CACHE_LIMIT + 1);
        Ok((*self
            .state
            .lock()
            .insert(path.to_path_buf(), stamp, timeline, bytes))
        .clone())
    }
}

struct ByteCounter(usize);

impl Write for ByteCounter {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.0 = self.0.saturating_add(bytes.len());
        Ok(bytes.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

pub async fn history(path: &Path) -> Result<Vec<TimelineItem>> {
    use futures_util::StreamExt;
    use tokio_util::codec::{FramedRead, LinesCodec};
    let file = tokio::fs::File::open(path).await?;
    let mut lines = FramedRead::new(file, LinesCodec::new_with_max_length(16 * 1024 * 1024));
    let mut entries = HashMap::<String, Value>::new();
    let mut leaf = String::new();
    while let Some(line) = lines.next().await {
        let line = line?;
        let Ok(entry) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        let id = omp::string(&entry, "id");
        if !id.is_empty() {
            leaf = id.into();
            entries.insert(id.into(), entry);
        }
    }
    let mut chain = vec![];
    let mut visited = std::collections::HashSet::new();
    while !leaf.is_empty() && visited.insert(leaf.clone()) {
        let Some(entry) = entries.get(&leaf) else {
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
    trim_timeline(&mut timeline, TIMELINE_LIMIT);
    Ok(timeline)
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
