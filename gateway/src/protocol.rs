use crate::domain::{OperationRecord, RuntimeSnapshot, SessionRecord, SessionView, WorkTiming};
use crate::{
    model::{Host, TimelineItem},
    workspace::Directory,
};
use chrono::{DateTime, Utc};
use serde::Serialize;

pub const GATEWAY_PROTOCOL_VERSION: u32 = 3;

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SessionOrigin {
    Managed,
    Discovered,
}

/// Public models deliberately exclude persistence and dispatch bookkeeping.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionDto {
    pub origin: SessionOrigin,
    pub id: String,
    pub host_id: String,
    pub cwd: String,
    pub title: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}
impl From<&SessionRecord> for SessionDto {
    fn from(s: &SessionRecord) -> Self {
        Self {
            origin: SessionOrigin::Managed,
            id: s.id.clone(),
            host_id: s.host_id.clone(),
            cwd: s.cwd.clone(),
            title: s.title.clone(),
            created_at: s.created_at,
            updated_at: s.updated_at,
        }
    }
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeDto {
    pub generation: String,
    pub state: &'static str,
    pub activity: Option<String>,
    pub model: Option<crate::model::ModelInfo>,
    pub pending_inputs: Vec<crate::model::Attention>,
    pub work_timing: Option<WorkTiming>,
}
impl From<&RuntimeSnapshot> for RuntimeDto {
    fn from(r: &RuntimeSnapshot) -> Self {
        Self {
            generation: r.generation.clone(),
            state: match r.phase.as_str() {
                "starting" => "starting",
                "stopping" => "stopping",
                _ if !r.pending_inputs.is_empty() => "waiting_input",
                _ if r.execution == "quiescent" => "idle",
                // Unknown execution is not safe to present as idle.
                _ if r.execution == "unknown" => "starting",
                _ => "running",
            },
            activity: r.activity.clone(),
            model: r.actual_model.clone(),
            pending_inputs: r.pending_inputs.clone(),
            work_timing: r.work_timing.clone(),
        }
    }
}
#[derive(Clone, Debug, Serialize)]
pub struct OperationDto {
    pub id: String,
    pub kind: String,
    pub state: &'static str,
    pub error: Option<OperationErrorDto>,
}
#[derive(Clone, Debug, Serialize)]
pub struct OperationErrorDto {
    pub code: String,
    pub message: &'static str,
}
impl From<&OperationRecord> for OperationDto {
    fn from(o: &OperationRecord) -> Self {
        Self {
            id: o.command_id.clone(),
            kind: o.command_type.clone(),
            state: match o.status.as_str() {
                "accepted" | "dispatching" | "running" => "pending",
                "succeeded" => "succeeded",
                "failed" => "failed",
                "cancelled" => "cancelled",
                _ => "unknown",
            },
            error: o.error.as_ref().map(|e| {
                let code = e["code"].as_str().unwrap_or("operation_failed");
                OperationErrorDto {
                    code: code.into(),
                    message: match code {
                        "external_session_busy" => "Close the external OMP session and retry",
                        "history_unavailable" => "OMP history is unavailable; refresh and retry",
                        "generation_mismatch" => "Runtime generation changed",
                        "input_pending" => "Answer the pending input first",
                        "runtime_required" => "No attached runtime",
                        "runtime_stopping" => "Runtime is not accepting commands",
                        "outcome_unknown" => "Command outcome could not be confirmed",
                        "persistence_unavailable" => "Command receipt could not be saved",
                        "invalid_file" => "Prompt attachment is unavailable or invalid",
                        _ => "Command did not complete successfully",
                    },
                }
            }),
        }
    }
}
#[derive(Clone, Debug, Serialize)]
pub struct SessionSummary {
    pub session: SessionDto,
    pub runtime: Option<RuntimeDto>,
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionSnapshot {
    #[serde(flatten)]
    pub summary: SessionSummary,
    pub timeline: Vec<TimelineItem>,
    pub operations: Vec<OperationDto>,
    pub has_history: bool,
    /// Optional first page requested by subscribe; older clients keep using REST.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub history: Option<serde_json::Value>,
}
impl SessionView {
    pub fn dto(&self) -> SessionSnapshot {
        SessionSnapshot {
            summary: SessionSummary {
                session: SessionDto {
                    origin: self.origin,
                    ..SessionDto::from(&self.session)
                },
                runtime: self.runtime.as_ref().map(RuntimeDto::from),
            },
            timeline: self.messages.clone(),
            operations: self
                .recent_operations
                .iter()
                .map(OperationDto::from)
                .collect(),
            has_history: self.history_ref.is_some(),
            history: None,
        }
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(
    tag = "type",
    rename_all = "snake_case",
    rename_all_fields = "camelCase"
)]
pub enum ServerEvent {
    HostSnapshot {
        protocol_version: u32,
        host: Host,
        sessions: Vec<SessionSummary>,
        workspaces: Vec<Directory>,
    },
    SessionSnapshot {
        session_id: String,
        #[serde(flatten)]
        snapshot: SessionSnapshot,
    },
    SessionUpsert {
        session_id: String,
        #[serde(flatten)]
        summary: SessionSummary,
    },
    SessionState {
        session_id: String,
        #[serde(flatten)]
        summary: SessionSummary,
        has_history: bool,
    },
    Timeline {
        session_id: String,
        upsert: Vec<TimelineItem>,
        remove: Vec<String>,
        reset: bool,
    },
    Operation {
        session_id: String,
        operation: OperationDto,
    },
}
impl ServerEvent {
    pub fn session_id(&self) -> Option<&str> {
        match self {
            Self::HostSnapshot { .. } => None,
            Self::SessionSnapshot { session_id, .. }
            | Self::SessionUpsert { session_id, .. }
            | Self::SessionState { session_id, .. }
            | Self::Timeline { session_id, .. }
            | Self::Operation { session_id, .. } => Some(session_id),
        }
    }
    pub fn updates_host(&self) -> bool {
        matches!(self, Self::SessionUpsert { .. } | Self::SessionState { .. })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn receipt_projection_hides_dispatch_and_persistence_fields() {
        let mut receipt = OperationRecord {
            command_id: "command".into(),
            client_id: "private".into(),
            session_id: "session".into(),
            command_type: "stop_runtime".into(),
            request_fingerprint: "private".into(),
            runtime_generation: Some("g".into()),
            status: String::new(),
            result: Some(json!({"internal":"private"})),
            error: None,
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };
        for (internal, public) in [
            ("accepted", "pending"),
            ("dispatching", "pending"),
            ("running", "pending"),
            ("succeeded", "succeeded"),
            ("failed", "failed"),
            ("cancelled", "cancelled"),
            ("outcome_unknown", "unknown"),
        ] {
            receipt.status = internal.into();
            assert_eq!(
                serde_json::to_value(OperationDto::from(&receipt)).unwrap(),
                json!({"id":"command","kind":"stop_runtime","state":public,"error":null})
            );
        }
        receipt.error = Some(
            json!({"code":"generation_mismatch","message":"private database error","stack":"private"}),
        );
        assert_eq!(
            serde_json::to_value(OperationDto::from(&receipt)).unwrap()["error"],
            json!({"code":"generation_mismatch","message":"Runtime generation changed"})
        );
    }
}
