use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
pub const PROTOCOL_VERSION: u32 = 1;
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
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub id: String,
    pub host_id: String,
    pub cwd: String,
    pub title: String,
    pub status: String,
    pub activity: String,
    pub needs_attention: bool,
    #[serde(default)]
    pub runtime_attached: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub attention: Option<Attention>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    #[serde(skip)]
    pub session_file: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TimelineItem {
    pub id: String,
    pub kind: String,
    pub text: String,
    #[serde(default)]
    pub detail: String,
    pub timestamp: DateTime<Utc>,
}
#[derive(Clone, Debug, Serialize)]
pub struct Detail {
    pub session: Session,
    pub timeline: Vec<TimelineItem>,
}
#[derive(Clone, Debug, Serialize)]
pub struct Event {
    pub sequence: u64,
    #[serde(rename = "type")]
    pub kind: String,
    pub timestamp: DateTime<Utc>,
    pub payload: serde_json::Value,
}
