//! Connection-local projection: compact list state and shared Todo payloads.
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    sync::Arc,
};

/// Order-independent metadata ledger. Updating a row removes its old SHA-256
/// contribution and adds its new one, avoiding a full sort/hash for runtime events.
#[derive(Clone, Debug, Default)]
pub struct Catalog {
    entries: Arc<HashMap<String, [u8; 32]>>,
    digest: [u8; 32],
}
impl Catalog {
    fn xor(&mut self, hash: [u8; 32]) {
        for (target, byte) in self.digest.iter_mut().zip(hash) {
            *target ^= byte;
        }
    }
    pub fn insert(&mut self, session: &Value) {
        let id = session["id"].as_str().expect("typed session identity");
        let hash: [u8; 32] = Sha256::digest(serde_json::to_vec(session).unwrap()).into();
        if self.entries.get(id) == Some(&hash) {
            return;
        }
        if let Some(old) = Arc::make_mut(&mut self.entries).insert(id.into(), hash) {
            self.xor(old);
        }
        self.xor(hash);
    }
    pub fn remove(&mut self, id: &str) {
        if let Some(old) = Arc::make_mut(&mut self.entries).remove(id) {
            self.xor(old);
        }
    }
    pub fn count(&self) -> usize {
        self.entries.len()
    }
    pub fn version(&self) -> String {
        format!("{}-{}", self.count(), hex::encode(self.digest))
    }
}

#[derive(Default)]
pub struct WireState {
    metadata: HashMap<String, Value>,
    todos: HashSet<String>,
    pub focus: Option<String>,
    catalog: Catalog,
}

pub fn compact_runtime(runtime: &mut Value) {
    if let Some(runtime) = runtime.as_object_mut() {
        let count = runtime
            .remove("pendingInputs")
            .and_then(|v| v.as_array().map(Vec::len))
            .unwrap_or(0);
        runtime.insert("pendingInputCount".into(), json!(count));
        runtime.remove("model");
    }
}

impl WireState {
    pub fn catalog_version(&self) -> String {
        self.catalog.version()
    }
    pub fn catalog_count(&self) -> usize {
        self.catalog.count()
    }
    pub fn seed_catalog(&mut self, catalog: Catalog) {
        self.catalog = catalog;
    }
    pub fn project(
        &mut self,
        mut value: Value,
        subscribed: bool,
        epoch: &str,
        sequence: u64,
    ) -> Value {
        value["epoch"] = json!(epoch);
        value["sequence"] = json!(sequence);
        if value["type"] == "host_snapshot" {
            self.metadata.clear();
            let sessions = value["sessions"].as_array_mut().unwrap();
            for summary in sessions {
                if let Some(id) = summary["session"]["id"].as_str() {
                    self.metadata.insert(id.into(), summary["session"].clone());
                }
                compact_runtime(&mut summary["runtime"]);
            }
            value["totalSessions"] = json!(self.catalog.count());
        } else if value["type"] == "session_state" || value["type"] == "session_upsert" {
            if !subscribed {
                compact_runtime(&mut value["runtime"]);
            }
            if let Some(id) = value["sessionId"].as_str().map(str::to_owned) {
                let session = value["session"].clone();
                self.catalog.insert(&session);
                value["totalSessions"] = json!(self.catalog.count());
                if value["type"] == "session_state" && self.metadata.get(&id) == Some(&session) {
                    value.as_object_mut().unwrap().remove("session");
                    value["type"] = json!("runtime_state");
                } else {
                    self.metadata.insert(id, session);
                }
            }
        }
        if value["type"] == "session_remove" {
            if let Some(id) = value["sessionId"].as_str() {
                self.catalog.remove(id);
                self.metadata.remove(id);
            }
            value["totalSessions"] = json!(self.catalog.count());
        }
        if matches!(
            value["type"].as_str(),
            Some(
                "host_snapshot"
                    | "session_state"
                    | "session_upsert"
                    | "runtime_state"
                    | "session_remove"
            )
        ) {
            value["catalogVersion"] = json!(self.catalog_version());
        }
        share_todos(&mut value, &mut self.todos);
        value
    }
}

/// Todo sharing is limited to timeline fields in the public response contract.
#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct WireTodoReference {
    todo_ref: String,
}

pub fn share_todos(value: &mut Value, known: &mut HashSet<String>) {
    fn share_item(
        item: &mut Value,
        known: &mut HashSet<String>,
        plans: &mut HashMap<String, Vec<crate::model::TodoPhase>>,
    ) {
        let Some(tool) = item.get_mut("tool").and_then(Value::as_object_mut) else {
            return;
        };
        let Some(phases) = tool.remove("todoPhases") else {
            return;
        };
        let phases: Vec<crate::model::TodoPhase> =
            serde_json::from_value(phases).expect("typed ToolTrace Todo phases");
        let version = hex::encode(Sha256::digest(serde_json::to_vec(&phases).unwrap()));
        let reference = serde_json::to_value(WireTodoReference {
            todo_ref: version.clone(),
        })
        .unwrap();
        tool.extend(reference.as_object().unwrap().clone());
        if known.insert(version.clone()) {
            plans.insert(version, phases);
        }
    }
    fn share_page(
        page: &mut Value,
        known: &mut HashSet<String>,
        plans: &mut HashMap<String, Vec<crate::model::TodoPhase>>,
    ) {
        if let Some(items) = page.get_mut("items").and_then(Value::as_array_mut) {
            for item in items {
                share_item(item, known, plans);
            }
        }
        if let Some(confirmed) = page.get_mut("confirmed").and_then(Value::as_array_mut) {
            for confirmation in confirmed {
                if let Some(item) = confirmation.get_mut("item") {
                    share_item(item, known, plans);
                }
            }
        }
    }
    let mut plans = HashMap::new();
    for field in ["timeline", "upsert"] {
        if let Some(items) = value.get_mut(field).and_then(Value::as_array_mut) {
            for item in items {
                share_item(item, known, &mut plans);
            }
        }
    }
    if let Some(item) = value.get_mut("metadata") {
        share_item(item, known, &mut plans);
    }
    share_page(value, known, &mut plans);
    if let Some(history) = value.get_mut("history") {
        share_page(history, known, &mut plans);
    }
    if !plans.is_empty() {
        value["todoPlans"] = serde_json::to_value(plans).unwrap();
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn todo_sharing_only_projects_timeline_tools_and_covers_inline_history() {
        let phases = json!([{"name":"ship","tasks":[{"content":"verify","status":"pending"}]}]);
        let unrelated = json!({"todoPhases": phases, "todoRef":"user text"});
        let mut value = json!({"metadata":{"tool":{"todoPhases":phases}},
            "history":{"items":[{"tool":{"todoPhases":phases}}],
                "confirmed":[{"item":{"tool":{"todoPhases":phases}}}]},
            "other":unrelated});
        share_todos(&mut value, &mut HashSet::new());
        assert_eq!(value["other"], unrelated);
        assert_eq!(value["todoPlans"].as_object().unwrap().len(), 1);
        let reference = &value["metadata"]["tool"]["todoRef"];
        assert!(reference.is_string());
        assert_eq!(&value["history"]["items"][0]["tool"]["todoRef"], reference);
        assert_eq!(
            &value["history"]["confirmed"][0]["item"]["tool"]["todoRef"],
            reference
        );
    }

    #[test]
    fn bounded_snapshot_keeps_supplied_cursor_and_complete_ledger() {
        let sessions: Vec<_> = (0..1000).map(|i| json!({"session":{"id":format!("s{i}"),"createdAt":"2026-01-01T00:00:00Z"},"runtime":null})).collect();
        let mut catalog = Catalog::default();
        for summary in &sessions {
            catalog.insert(&summary["session"]);
        }
        let initial_version = catalog.version();
        let mut wire = WireState::default();
        wire.seed_catalog(catalog.clone());
        let selected: Vec<_> = sessions
            .iter()
            .take(50)
            .cloned()
            .chain([sessions[800].clone(), sessions[900].clone()])
            .collect();
        let small = wire.project(
            json!({"type":"host_snapshot","sessions":selected,"nextSessionsCursor":"base-cursor"}),
            false,
            "epoch",
            0,
        );
        assert_eq!(small["totalSessions"], 1000);
        assert_eq!(small["sessions"].as_array().unwrap().len(), 52);
        assert_eq!(small["nextSessionsCursor"], "base-cursor");
        let runtime = wire.project(json!({"type":"session_state","sessionId":"s0","session":sessions[0]["session"],"runtime":{"state":"running","pendingInputs":[]}}),false,"epoch",1);
        assert_eq!(runtime["type"], "runtime_state");
        assert_eq!(wire.catalog_version(), initial_version);
        let changed = json!({"id":"s900","title":"changed"});
        wire.project(
            json!({"type":"session_state","sessionId":"s900","session":changed,"runtime":null}),
            false,
            "epoch",
            2,
        );
        catalog.insert(&changed);
        assert_eq!(wire.catalog_version(), catalog.version());
        wire.project(
            json!({"type":"session_remove","sessionId":"s900"}),
            false,
            "epoch",
            3,
        );
        catalog.remove("s900");
        assert_eq!(wire.catalog_version(), catalog.version());
        assert_eq!(wire.catalog_count(), 999);
    }
    #[test]
    fn repeated_todo_payload_is_shared_once_per_connection() {
        let phases = json!([{ "name":"ship","tasks":[{"content":"verify","status":"pending"}]}]);
        let mut wire = WireState::default();
        let first = wire.project(json!({"type":"timeline","upsert":[{"tool":{"todoPhases":phases}},{"tool":{"todoPhases":phases}}]}),true,"e",1);
        assert_eq!(first["todoPlans"].as_object().unwrap().len(), 1);
        assert_eq!(
            first["upsert"][0]["tool"]["todoRef"],
            first["upsert"][1]["tool"]["todoRef"]
        );
        let second = wire.project(
            json!({"type":"timeline","upsert":[{"tool":{"todoPhases":phases}}]}),
            true,
            "e",
            2,
        );
        assert!(second.get("todoPlans").is_none());
    }
}
