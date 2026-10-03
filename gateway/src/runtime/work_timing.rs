use super::ControllerState;
use crate::domain::{RuntimeSnapshot, WorkTiming};
use std::time::{Duration, Instant};

/// Monotonic, generation-scoped authority. Never cloned into a snapshot.
pub(super) struct WorkClock {
    elapsed: Duration,
    running_since: Option<Instant>,
    completed: bool,
}

impl WorkClock {
    fn elapsed_at(&self, now: Instant) -> Duration {
        self.elapsed
            + self
                .running_since
                .map_or(Duration::ZERO, |start| now.saturating_duration_since(start))
    }

    fn sample(&self, now: Instant) -> WorkTiming {
        WorkTiming {
            elapsed_ms: self.elapsed_at(now).as_millis().min(u64::MAX as u128) as u64,
            running: self.running_since.is_some(),
            completed: self.completed,
        }
    }
}

impl ControllerState {
    /// Capture while holding the state lock, before cloning or serializing a projection.
    pub(super) fn capture_runtime(&mut self) -> &Option<RuntimeSnapshot> {
        self.capture_runtime_at(Instant::now())
    }

    fn capture_runtime_at(&mut self, now: Instant) -> &Option<RuntimeSnapshot> {
        if let Some(snapshot) = self.projection.as_mut() {
            snapshot.work_timing = self.work_clock.as_ref().map(|clock| clock.sample(now));
        }
        &self.projection
    }

    pub(super) fn update_work_timing(&mut self, begin: bool) {
        self.update_work_timing_at(begin, Instant::now());
    }

    fn update_work_timing_at(&mut self, begin: bool, now: Instant) {
        let Some(snapshot) = self.projection.as_ref() else {
            self.work_clock = None;
            return;
        };
        if snapshot.execution == "unknown" {
            self.work_clock = None;
            return;
        }
        if !begin
            && snapshot.execution == "active"
            && self
                .work_clock
                .as_ref()
                .is_some_and(|clock| clock.completed)
        {
            self.work_clock = None;
            return;
        }
        if begin && self.work_clock.as_ref().is_none_or(|clock| clock.completed) {
            self.work_clock = Some(WorkClock {
                elapsed: Duration::ZERO,
                running_since: None,
                completed: false,
            });
        }
        let Some(clock) = self.work_clock.as_mut() else {
            return;
        };
        clock.elapsed = clock.elapsed_at(now);
        clock.completed = snapshot.execution == "quiescent";
        clock.running_since =
            (snapshot.execution == "active" && snapshot.pending_inputs.is_empty()).then_some(now);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{domain::SessionRecord, model::Attention};
    use serde_json::json;

    fn state() -> ControllerState {
        let now = chrono::Utc::now();
        ControllerState {
            session: SessionRecord {
                id: "s".into(),
                host_id: "h".into(),
                cwd: "/".into(),
                title: "Work".into(),
                metadata_revision: 1,
                created_at: now,
                updated_at: now,
                archived_at: None,
                engine_session_ref: None,
            },
            runtime: None,
            projection: Some(RuntimeSnapshot {
                generation: "g".into(),
                phase: "ready".into(),
                execution: "active".into(),
                activity: None,
                actual_model: None,
                pending_inputs: vec![],
                work_timing: None,
            }),
            work_clock: None,
            messages: Vec::new(),
            finalized_messages: Default::default(),
            pending_prompt_results: Default::default(),
            settled_revision: 0,
            dirty_messages: Default::default(),
            published_messages: Default::default(),
            removed_messages: Vec::new(),
            display_flush_scheduled: false,
        }
    }

    fn attention() -> Attention {
        Attention {
            id: "a".into(),
            kind: "confirm".into(),
            text: "?".into(),
            options: vec![],
        }
    }

    fn timing(state: &mut ControllerState, now: Instant) -> Option<WorkTiming> {
        state
            .capture_runtime_at(now)
            .as_ref()
            .unwrap()
            .work_timing
            .clone()
    }

    #[test]
    fn work_time_pauses_for_input_and_resets_only_for_new_work() {
        let mut state = state();
        let start = Instant::now();
        state.update_work_timing_at(true, start);
        state.update_work_timing_at(true, start + Duration::from_secs(5));
        state
            .projection
            .as_mut()
            .unwrap()
            .pending_inputs
            .push(attention());
        state.update_work_timing_at(false, start + Duration::from_secs(10));
        assert_eq!(
            timing(&mut state, start + Duration::from_secs(30)),
            Some(WorkTiming {
                elapsed_ms: 10_000,
                running: false,
                completed: false,
            })
        );
        state.projection.as_mut().unwrap().pending_inputs.clear();
        state.update_work_timing_at(false, start + Duration::from_secs(30));
        state.projection.as_mut().unwrap().execution = "quiescent".into();
        state.update_work_timing_at(false, start + Duration::from_secs(35));
        let completed = Some(WorkTiming {
            elapsed_ms: 15_000,
            running: false,
            completed: true,
        });
        assert_eq!(
            timing(&mut state, start + Duration::from_secs(50)),
            completed
        );
        state.update_work_timing_at(false, start + Duration::from_secs(55));
        assert_eq!(
            timing(&mut state, start + Duration::from_secs(55)),
            completed
        );
        state.projection.as_mut().unwrap().execution = "active".into();
        state.update_work_timing_at(true, start + Duration::from_secs(60));
        assert_eq!(
            timing(&mut state, start + Duration::from_secs(60)),
            Some(WorkTiming {
                elapsed_ms: 0,
                running: true,
                completed: false,
            })
        );
        state.projection.as_mut().unwrap().execution = "unknown".into();
        state.update_work_timing_at(false, start + Duration::from_secs(70));
        assert_eq!(timing(&mut state, start + Duration::from_secs(70)), None);
        state.projection.as_mut().unwrap().execution = "active".into();
        state.update_work_timing_at(false, start + Duration::from_secs(80));
        assert_eq!(
            timing(&mut state, start + Duration::from_secs(80)),
            None,
            "A state query cannot invent a start time"
        );
    }

    #[test]
    fn captured_snapshot_is_immutable_while_authority_runs_and_pauses() {
        let mut state = state();
        let start = Instant::now();
        state.update_work_timing_at(true, start);
        let captured = state
            .capture_runtime_at(start + Duration::from_secs(5))
            .clone()
            .unwrap();
        let expected = json!({"elapsedMs":5000,"running":true,"completed":false});
        assert_eq!(
            serde_json::to_value(crate::protocol::RuntimeDto::from(&captured)).unwrap()["workTiming"],
            expected
        );
        assert_eq!(
            timing(&mut state, start + Duration::from_secs(10))
                .unwrap()
                .elapsed_ms,
            10_000
        );
        assert_eq!(
            serde_json::to_value(crate::protocol::RuntimeDto::from(&captured)).unwrap()["workTiming"],
            expected
        );
        state
            .projection
            .as_mut()
            .unwrap()
            .pending_inputs
            .push(attention());
        state.update_work_timing_at(false, start + Duration::from_secs(15));
        let paused = state
            .capture_runtime_at(start + Duration::from_secs(30))
            .clone()
            .unwrap();
        assert_eq!(
            serde_json::to_value(crate::protocol::RuntimeDto::from(&paused)).unwrap()["workTiming"],
            json!({"elapsedMs":15000,"running":false,"completed":false})
        );
        assert_eq!(
            serde_json::to_value(crate::protocol::RuntimeDto::from(&captured)).unwrap()["workTiming"],
            expected
        );
        assert!(captured.pending_inputs.is_empty());
        assert_eq!(paused.pending_inputs[0].id, "a");
    }
}
