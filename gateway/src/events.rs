use crate::protocol::ServerEvent;
use parking_lot::Mutex;
use std::{collections::HashMap, sync::Arc};
use tokio::sync::broadcast;

#[derive(Clone, Debug)]
pub enum Event {
    Update {
        sequence: u64,
        event: Arc<ServerEvent>,
    },
    Disconnect,
}
struct State {
    sequence: u64,
    host: u64,
    sessions: HashMap<String, u64>,
    sender: broadcast::Sender<Event>,
}
pub struct Bus {
    state: Mutex<State>,
}
impl Default for Bus {
    fn default() -> Self {
        let (sender, _) = broadcast::channel(32);
        Self {
            state: Mutex::new(State {
                sequence: 0,
                host: 0,
                sessions: HashMap::new(),
                sender,
            }),
        }
    }
}
impl Bus {
    pub fn publish(&self, event: ServerEvent) {
        let mut state = self.state.lock();
        state.sequence += 1;
        let sequence = state.sequence;
        if event.updates_host() {
            state.host = sequence;
        }
        if let Some(id) = event.session_id() {
            state.sessions.insert(id.into(), sequence);
        }
        let oversized = serde_json::to_vec(&event).map_or(true, |v| v.len() > 256 * 1024);
        let _ = state.sender.send(if oversized {
            Event::Disconnect
        } else {
            Event::Update {
                sequence,
                event: Arc::new(event),
            }
        });
    }
    /// Only mutations represented by this snapshot invalidate it. Markers stay server-local.
    pub fn sequence(&self, session_id: Option<&str>) -> u64 {
        let state = self.state.lock();
        match session_id {
            Some(id) => state.sessions.get(id).copied().unwrap_or(0),
            None => state.host,
        }
    }
    pub fn disconnect(&self) {
        let _ = self.state.lock().sender.send(Event::Disconnect);
    }
    pub fn subscribe(&self) -> broadcast::Receiver<Event> {
        self.state.lock().sender.subscribe()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{SessionDto, SessionSummary};
    #[test]
    fn snapshots_are_invalidated_only_by_mutations_they_represent() {
        let bus = Bus::default();
        for _ in 0..100 {
            bus.publish(ServerEvent::Timeline {
                session_id: "a".into(),
                upsert: vec![],
                remove: vec![],
                reset: false,
            });
        }
        assert_eq!(
            bus.sequence(None),
            0,
            "timeline is not part of a host snapshot"
        );
        assert_eq!(
            bus.sequence(Some("b")),
            0,
            "another session cannot invalidate b"
        );
        assert_eq!(bus.sequence(Some("a")), 100);
        bus.publish(ServerEvent::SessionState {
            session_id: "a".into(),
            has_history: false,
            summary: SessionSummary {
                runtime: None,
                session: SessionDto {
                    origin: crate::protocol::SessionOrigin::Managed,
                    id: "a".into(),
                    host_id: "h".into(),
                    cwd: "/work".into(),
                    title: "a".into(),
                    created_at: chrono::Utc::now(),
                    updated_at: chrono::Utc::now(),
                },
            },
        });
        assert_eq!(
            bus.sequence(None),
            101,
            "state changes invalidate the host snapshot"
        );
        assert_eq!(bus.sequence(Some("a")), 101);
        assert_eq!(bus.sequence(Some("b")), 0);
    }
}
