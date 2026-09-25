use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const GATEWAY_PROTOCOL_VERSION: u32 = 2;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Cursor {
    pub epoch: String,
    pub revision: u64,
}

/// Gateway-owned identity and display metadata. Process and interaction facts live elsewhere.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionRecord {
    pub id: String,
    pub host_id: String,
    pub cwd: String,
    pub title: String,
    pub metadata_revision: i64,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub archived_at: Option<DateTime<Utc>>,
    #[serde(skip)]
    pub engine_session_ref: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
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

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeSnapshot {
    pub generation: String,
    pub phase: String,
    pub execution: String,
    pub activity: Option<String>,
    pub actual_model: Option<crate::model::ModelInfo>,
    pub pending_inputs: Vec<crate::model::Attention>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionView {
    pub session: SessionRecord,
    pub runtime: Option<RuntimeSnapshot>,
    pub recent_operations: Vec<OperationRecord>,
    pub messages: Vec<crate::model::TimelineItem>,
    pub history_ref: Option<String>,
}
