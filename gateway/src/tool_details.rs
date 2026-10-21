//! Compact timeline projections and versioned, UTF-8 safe tool-detail pages.
use crate::{
    model::{TimelineItem, ToolSummary, ToolTrace},
    omp,
};
use anyhow::{Result, ensure};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

fn brief(value: &str) -> String {
    value.chars().take(240).collect()
}

/// OMP's anchored edit headers carry a path followed by a content hash.
fn anchored_edit_path(line: &str) -> Option<&str> {
    let (path, hash) = line
        .strip_prefix('[')?
        .strip_suffix(']')?
        .rsplit_once('#')?;
    (!path.is_empty()
        && (4..=64).contains(&hash.len())
        && hash.bytes().all(|b| b.is_ascii_hexdigit()))
    .then_some(path)
}

fn patch_header(value: &str) -> Option<&str> {
    value
        .lines()
        .find(|line| !line.trim().is_empty() && *line != "*** Begin Patch")
}

fn is_anchored_edit(value: &str) -> bool {
    patch_header(value).and_then(anchored_edit_path).is_some()
}

fn preview<'a>(lines: impl Iterator<Item = &'a str>) -> String {
    let mut result = String::new();
    let mut remaining = 360;
    for line in lines.filter(|line| !line.trim().is_empty()).take(3) {
        if !result.is_empty() {
            result.push('\n');
            remaining -= 1;
        }
        for character in line.chars().take(remaining) {
            result.push(character);
            remaining -= 1;
        }
        if remaining == 0 {
            break;
        }
    }
    result
}

impl ToolTrace {
    pub fn details_version(&self) -> String {
        let bytes = serde_json::to_vec(&(
            &self.arguments,
            if self.completed { &self.result } else { "" },
            self.completed,
            self.is_error,
            &self.todo_phases,
        ))
        .unwrap_or_default();
        hex::encode(Sha256::digest(bytes))
    }

    pub fn summary(&self) -> Self {
        let first = |keys: &[&str]| {
            keys.iter()
                .find_map(|key| {
                    self.arguments[*key]
                        .as_str()
                        .filter(|s| !s.trim().is_empty())
                })
                .map(brief)
                .unwrap_or_default()
        };
        let is_edit = self.name.rsplit(['.', '/']).next() == Some("edit");
        let input_patch = self.arguments["input"].as_str().filter(|input| {
            (is_edit && is_anchored_edit(input))
                || input.lines().find(|line| !line.trim().is_empty()) == Some("*** Begin Patch")
        });
        let anchored_input = is_edit && input_patch.is_some_and(is_anchored_edit);
        let mut files: Vec<String> = ["path", "file", "filePath", "file_path", "filename"]
            .iter()
            .filter_map(|key| self.arguments[*key].as_str())
            .map(brief)
            .collect();
        for key in ["files", "paths"] {
            if let Some(paths) = self.arguments[key].as_array() {
                files.extend(paths.iter().filter_map(Value::as_str).take(32).map(brief));
            }
        }
        for value in [
            self.arguments["patch"].as_str(),
            self.arguments["diff"].as_str(),
            input_patch,
            Some(self.result.as_str()),
        ]
        .into_iter()
        .flatten()
        {
            let anchored = is_edit && is_anchored_edit(value);
            for line in value.lines() {
                let path = if anchored {
                    anchored_edit_path(line)
                } else {
                    [
                        "*** Update File: ",
                        "*** Add File: ",
                        "*** Delete File: ",
                        "+++ b/",
                    ]
                    .iter()
                    .find_map(|prefix| line.strip_prefix(prefix))
                };
                if let Some(path) = path
                    && files.len() < 32
                {
                    files.push(brief(path));
                }
            }
        }
        files.sort();
        files.dedup();
        files.truncate(32);
        let subject = first(&["command", "cmd", "query", "pattern", "task", "code"]);
        let target = first(&[
            "path",
            "file",
            "filePath",
            "file_path",
            "filename",
            "url",
            "uri",
            "cwd",
        ]);
        let action = first(&["i", "description", "title"]);
        let action = if action.is_empty() && is_edit {
            files
                .first()
                .map(|path| format!("Edit {}", path.rsplit('/').next().unwrap_or(path)))
                .unwrap_or(action)
        } else {
            action
        };
        let patch_preview = input_patch
            .map(|patch| {
                preview(patch.lines().filter(|line| {
                    if anchored_input {
                        line.starts_with('+')
                    } else {
                        (line.starts_with('+') && !line.starts_with("+++"))
                            || (line.starts_with('-') && !line.starts_with("---"))
                    }
                }))
            })
            .unwrap_or_default();
        let summary = Some(ToolSummary {
            action,
            target: brief(
                &[subject, target]
                    .into_iter()
                    .filter(|s| !s.is_empty())
                    .collect::<Vec<_>>()
                    .join(", "),
            ),
            files,
            preview: if self.is_error {
                String::new()
            } else if !patch_preview.is_empty() {
                patch_preview
            } else if self.completed {
                preview(self.result.lines())
            } else {
                String::new()
            },
            error: if self.is_error {
                brief(&self.result)
            } else {
                String::new()
            },
        });
        Self {
            call_id: self.call_id.clone(),
            name: self.name.clone(),
            arguments: Value::Null,
            result: String::new(),
            is_error: self.is_error,
            completed: self.completed,
            todo_phases: self.todo_phases.clone(),
            summary,
            details_version: self.details_version(),
            details_available: !self.arguments.is_null()
                || !self.result.is_empty()
                || !self.completed,
        }
    }
}

impl TimelineItem {
    pub fn summary(&self) -> Self {
        Self {
            id: self.id.clone(),
            source_id: self.source_id.clone(),
            message_key: self.message_key.clone(),
            kind: self.kind.clone(),
            text: self.text.clone(),
            detail: self.detail.clone(),
            tool: self.tool.as_ref().map(ToolTrace::summary),
            timestamp: self.timestamp,
        }
    }
}

/// RPC messageId is process-local (msg-N), not the JSONL entry id. Use the actual
/// message timestamp and full text to correlate final records, never text alone.
pub fn message_source(message: &Value, entry_id: &str) -> String {
    if message["timestamp"].as_i64().is_some_and(|v| v > 0) {
        let key = json!([
            message["role"],
            message["timestamp"],
            omp::text_content(message)
        ]);
        format!(
            "message:{}",
            hex::encode(Sha256::digest(serde_json::to_vec(&key).unwrap()))
        )
    } else {
        format!("entry:{entry_id}")
    }
}

pub fn message_key(message: &Value) -> Option<String> {
    message["timestamp"]
        .as_i64()
        .filter(|timestamp| *timestamp > 0)
        .map(|timestamp| format!("{}:{timestamp}", omp::string(message, "role")))
}

pub fn detail_page(trace: &ToolTrace, cursor: Option<&str>) -> Result<Value> {
    let version = trace.details_version();
    let (start, expected_prefix) = match cursor {
        Some(cursor) => {
            let (expected, offset) = cursor
                .split_once(':')
                .ok_or_else(|| anyhow::anyhow!("invalid_detail_cursor"))?;
            (
                offset
                    .parse::<usize>()
                    .map_err(|_| anyhow::anyhow!("invalid_detail_cursor"))?,
                Some(expected),
            )
        }
        None => (0, None),
    };
    let mut text = String::new();
    if !trace.arguments.is_null() {
        text.push_str(&format!(
            "Arguments\n{}\n\n",
            serde_json::to_string_pretty(&trace.arguments)?
        ));
    }
    if !trace.result.is_empty() {
        text.push_str(&format!("Result\n{}", trace.result));
    }
    ensure!(
        start <= text.len() && text.is_char_boundary(start),
        if expected_prefix.is_some() {
            "stale_detail"
        } else {
            "invalid_detail_cursor"
        }
    );
    if let Some(expected) = expected_prefix {
        ensure!(
            expected == hex::encode(Sha256::digest(&text.as_bytes()[..start])),
            "stale_detail"
        );
    }
    let mut end = (start + 32 * 1024).min(text.len());
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    Ok(
        json!({"text":&text[start..end], "version":version, "completed":trace.completed,"hasMore":end < text.len(),
        "resumeCursor":format!("{}:{end}",hex::encode(Sha256::digest(&text.as_bytes()[..end]))),
        "nextCursor":(end < text.len() || !trace.completed).then(|| format!("{}:{end}",hex::encode(Sha256::digest(&text.as_bytes()[..end]))))}),
    )
}
