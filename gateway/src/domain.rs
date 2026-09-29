use chrono::{DateTime, Utc};
use serde::Serialize;
use serde_json::Value;

/// Gateway-owned identity and display metadata. Process and interaction facts live elsewhere.
#[derive(Clone, Debug)]
pub struct SessionRecord {
    pub id: String,
    pub host_id: String,
    pub cwd: String,
    pub title: String,
    pub metadata_revision: i64,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub archived_at: Option<DateTime<Utc>>,
    pub engine_session_ref: Option<String>,
}

#[derive(Clone, Debug)]
pub struct OperationRecord {
    pub command_id: String,
    pub client_id: String,
    pub session_id: String,
    pub command_type: String,
    pub request_fingerprint: String,
    pub runtime_generation: Option<String>,
    pub status: String,
    pub result: Option<Value>,
    pub error: Option<Value>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// Immutable work-time sample captured with the runtime projection.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkTiming {
    pub elapsed_ms: u64,
    pub running: bool,
    pub completed: bool,
}

#[derive(Clone, Debug)]
pub struct RuntimeSnapshot {
    pub generation: String,
    pub phase: String,
    pub execution: String,
    pub activity: Option<String>,
    pub actual_model: Option<crate::model::ModelInfo>,
    pub pending_inputs: Vec<crate::model::Attention>,
    pub work_timing: Option<WorkTiming>,
}

#[derive(Clone, Debug)]
pub struct SessionView {
    pub origin: crate::protocol::SessionOrigin,
    pub session: SessionRecord,
    pub runtime: Option<RuntimeSnapshot>,
    pub recent_operations: Vec<OperationRecord>,
    pub messages: Vec<crate::model::TimelineItem>,
    pub history_ref: Option<String>,
}
