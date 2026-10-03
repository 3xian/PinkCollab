use crate::protocol::ServerEvent;
use parking_lot::Mutex;
use std::{
    collections::{HashMap, VecDeque},
    sync::Arc,
    time::{Duration, Instant},
};
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
    replay: VecDeque<(Instant, usize, Event)>,
    replay_bytes: usize,
    receipts: HashMap<String, serde_json::Value>,
    evicted: HashMap<String, u64>,
}
pub struct Bus {
    state: Mutex<State>,
    pub epoch: String,
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
                replay: VecDeque::new(),
                replay_bytes: 0,
                receipts: HashMap::new(),
                evicted: HashMap::new(),
            }),
            epoch: format!("{:032x}", rand::random::<u128>()),
        }
    }
}
impl Bus {
    pub fn publish(&self, event: ServerEvent) {
        let mut state = self.state.lock();
        if let ServerEvent::Operation {
            session_id,
            operation,
        } = &event
        {
            let key = format!("{session_id}:{}", operation.id);
            let value = serde_json::to_value(operation).unwrap_or_default();
            if state.receipts.get(&key) == Some(&value) {
                return;
            }
            if state.receipts.len() >= 2048 {
                state.receipts.clear();
            }
            state.receipts.insert(key, value);
        }
        state.sequence += 1;
        let sequence = state.sequence;
        if event.updates_host() {
            state.host = sequence;
        }
        if let Some(id) = event.session_id() {
            state.sessions.insert(id.into(), sequence);
        }
        let bytes = serde_json::to_vec(&event).map_or(usize::MAX, |v| v.len());
        if bytes > 8 * 1024 * 1024 {
            if event.updates_host() {
                state.evicted.insert(String::new(), sequence);
            }
            if let Some(id) = event.session_id() {
                state.evicted.insert(id.into(), sequence);
            }
        }
        let update = if bytes > 8 * 1024 * 1024 {
            Event::Disconnect
        } else {
            Event::Update {
                sequence,
                event: Arc::new(event),
            }
        };
        if matches!(update, Event::Update { .. }) {
            state.replay_bytes += bytes;
            state
                .replay
                .push_back((Instant::now(), bytes, update.clone()));
            while state.replay_bytes > 8 * 1024 * 1024 || state.replay.len() > 1024 {
                if let Some((_, size, Event::Update { sequence, event })) = state.replay.pop_front()
                {
                    state.replay_bytes -= size;
                    if event.updates_host() {
                        state.evicted.insert(String::new(), sequence);
                    }
                    if let Some(id) = event.session_id() {
                        state.evicted.insert(id.into(), sequence);
                    }
                }
            }
        }
        let _ = state.sender.send(update);
    }
    pub fn replay(&self, epoch: &str, after: u64, scope: Option<&str>) -> Option<Vec<Event>> {
        if epoch != self.epoch {
            return None;
        }
        let state = self.state.lock();
        let current = match scope {
            Some(id) => state.sessions.get(id).copied().unwrap_or(0),
            None => state.host,
        };
        if after > current
            || state
                .evicted
                .get(scope.unwrap_or(""))
                .is_some_and(|last| *last > after)
        {
            return None;
        }
        let mut result = Vec::new();
        for (time, _, event) in &state.replay {
            let Event::Update {
                sequence,
                event: payload,
            } = event
            else {
                continue;
            };
            if *sequence <= after
                || !match scope {
                    Some(id) => payload.session_id() == Some(id),
                    None => payload.updates_host(),
                }
            {
                continue;
            }
            if time.elapsed() > Duration::from_secs(120) {
                return None;
            }
            result.push(event.clone());
        }
        Some(result)
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
    fn replay_is_scoped_and_unrelated_eviction_does_not_expire_an_idle_scope() {
        let bus = Bus::default();
        let event = |id: &str| ServerEvent::Timeline {
            session_id: id.into(),
            upsert: vec![],
            remove: vec![],
            reset: false,
        };
        bus.publish(event("idle"));
        let fence = bus.sequence(Some("idle"));
        for _ in 0..1100 {
            bus.publish(event("busy"));
        }
        assert!(
            bus.replay(&bus.epoch, fence, Some("idle"))
                .unwrap()
                .is_empty()
        );
        assert!(bus.replay(&bus.epoch, 0, None).unwrap().is_empty());
        assert!(bus.replay(&bus.epoch, 0, Some("busy")).is_none());
        assert!(
            bus.replay("previous-process", fence, Some("idle"))
                .is_none()
        );
        bus.publish(event("idle"));
        assert_eq!(
            bus.replay(&bus.epoch, fence, Some("idle")).unwrap().len(),
            1
        );
    }
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
