use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Host {
    pub id: String,
    pub name: String,
    pub os: String,
    pub status: String,
    pub omp_version: String,
    pub gateway_version: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Attention {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub text: String,
    #[serde(default)]
    pub options: Vec<String>,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelInfo {
    pub provider: String,
    pub id: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thinking_level: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TimelineItem {
    pub id: String,
    pub kind: String,
    pub text: String,
    #[serde(default)]
    pub detail: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool: Option<ToolTrace>,
    pub timestamp: DateTime<Utc>,
}

/// Structured tool data is part of the wire timeline only. OMP's session JSONL remains the
/// canonical, lossless transcript and PinkCollab still does not persist conversation content.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolTrace {
    pub call_id: String,
    pub name: String,
    #[serde(default)]
    pub arguments: serde_json::Value,
    #[serde(default)]
    pub result: String,
    #[serde(default)]
    pub is_error: bool,
    #[serde(default)]
    pub completed: bool,
}

impl ToolTrace {
    pub fn started(
        call_id: impl Into<String>,
        name: impl Into<String>,
        arguments: serde_json::Value,
    ) -> Self {
        Self {
            call_id: call_id.into(),
            name: name.into(),
            arguments,
            result: String::new(),
            is_error: false,
            completed: false,
        }
    }

    pub fn completed(
        call_id: impl Into<String>,
        name: impl Into<String>,
        result: impl Into<String>,
        is_error: bool,
    ) -> Self {
        Self {
            call_id: call_id.into(),
            name: name.into(),
            arguments: serde_json::Value::Null,
            result: result.into(),
            is_error,
            completed: true,
        }
    }

    /// Display text for a tool timeline item. Derived here so the constructors and the upsert path
    /// cannot disagree about how a call is labelled.
    fn label(&self) -> String {
        let state = match (self.completed, self.is_error) {
            (true, true) => "Tool failed",
            (true, false) => "Finished",
            _ => "Running",
        };
        format!("{state} · {}", self.name)
    }

    fn fill_from(&mut self, previous: &Self) {
        if self.call_id.is_empty() {
            self.call_id = previous.call_id.clone();
        }
        if self.name.is_empty() {
            self.name = previous.name.clone();
        }
        if self.arguments.is_null() {
            self.arguments = previous.arguments.clone();
        }
        if self.result.is_empty() {
            self.result = previous.result.clone();
        }
        // Start and result frames carry different halves of a call and can arrive in either order
        // once a transcript is replayed, so these flags only ever move one way.
        self.completed |= previous.completed;
        self.is_error |= previous.is_error;
    }
}

impl TimelineItem {
    pub fn tool_started(
        call_id: impl Into<String>,
        name: impl Into<String>,
        arguments: serde_json::Value,
        timestamp: DateTime<Utc>,
    ) -> Self {
        Self::tool(ToolTrace::started(call_id, name, arguments), timestamp)
    }

    pub fn tool_completed(
        call_id: impl Into<String>,
        name: impl Into<String>,
        result: impl Into<String>,
        is_error: bool,
        timestamp: DateTime<Utc>,
    ) -> Self {
        Self::tool(
            ToolTrace::completed(call_id, name, result, is_error),
            timestamp,
        )
    }

    /// Tool items carry their payload once, inside `tool`; `detail` stays empty because clients
    /// project the trace instead of parsing display text.
    fn tool(trace: ToolTrace, timestamp: DateTime<Utc>) -> Self {
        Self {
            id: trace.call_id.clone(),
            kind: "tool".into(),
            text: trace.label(),
            detail: String::new(),
            tool: Some(trace),
            timestamp,
        }
    }

    /// Applies a result update while retaining call metadata and the call's original position.
    pub fn merge_tool_update(&mut self, mut update: Self) {
        if let (Some(previous), Some(current)) = (&self.tool, &mut update.tool) {
            current.fill_from(previous);
            update.text = current.label();
        }
        update.timestamp = self.timestamp;
        *self = update;
    }
}
#[derive(Clone, Debug, Serialize)]
pub struct Event {
    pub sequence: u64,
    #[serde(rename = "type")]
    pub kind: String,
    pub timestamp: DateTime<Utc>,
    pub payload: serde_json::Value,
}
