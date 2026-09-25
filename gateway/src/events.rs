use crate::{model::Event, storage::id, v2_model::Cursor};
use chrono::Utc;
use parking_lot::Mutex;
use serde_json::{Value, json};
use std::collections::HashMap;
use tokio::sync::broadcast;

pub struct Bus {
    state: Mutex<(u64, broadcast::Sender<Event>)>,
    resources: Mutex<(String, HashMap<String, u64>)>,
}
impl Default for Bus {
    fn default() -> Self {
        let (tx, _) = broadcast::channel(32);
        Self {
            state: Mutex::new((0, tx)),
            resources: Mutex::new((id("view_"), HashMap::new())),
        }
    }
}
impl Bus {
    pub fn publish(&self, kind: &str, payload: Value) {
        let mut state = self.state.lock();
        state.0 += 1;
        let _ = state.1.send(Event {
            sequence: state.0,
            kind: kind.into(),
            timestamp: Utc::now(),
            payload,
        });
    }
    pub fn subscribe(&self) -> broadcast::Receiver<Event> {
        self.state.lock().1.subscribe()
    }
    pub fn cursor(&self, resource: &str) -> Cursor {
        let resources = self.resources.lock();
        Cursor {
            epoch: resources.0.clone(),
            revision: *resources.1.get(resource).unwrap_or(&0),
        }
    }
    pub fn publish_resource(&self, resource: &str, changes: Value) {
        let mut resources = self.resources.lock();
        let epoch = resources.0.clone();
        let current = resources.1.entry(resource.into()).or_insert(0);
        let base = *current;
        *current += 1;
        let payload = json!({"resource":resource,"epoch":epoch,"baseRevision":base,"revision":*current,"changes":changes});
        self.publish("change", payload);
    }
}
