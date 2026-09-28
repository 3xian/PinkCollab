use super::{CommandFailure, ControllerState, RuntimeSnapshot, SessionController};
use crate::{omp, uploads::PromptFiles};
use serde_json::{Value, json};
use std::sync::Arc;

/// Prepare both wire representations before process allocation or final state admission.
pub(super) struct PreparedPrompt {
    normal: Value,
    steer: Value,
}
impl PreparedPrompt {
    pub(super) fn new(id: &str, message: &str, files: PromptFiles) -> Result<Self, CommandFailure> {
        let normal =
            json!({"type":"prompt","id":id,"message":format!("{message}{}", files.direct_note)});
        let mut steer = json!({"type":"prompt","id":id,"message":format!("{message}{}", files.queued_note),"streamingBehavior":"steer"});
        if !files.images.is_empty() {
            steer["images"] = json!(files.images);
        }
        for frame in [&normal, &steer] {
            if serde_json::to_vec(frame).map_or(true, |bytes| bytes.len() >= omp::MAX_LINE) {
                return Err(CommandFailure::failed(
                    "invalid_request",
                    "Prompt and attachments exceed OMP input frame limit",
                ));
            }
        }
        Ok(Self { normal, steer })
    }
    pub(super) fn frame(self, steer: bool) -> Value {
        if steer { self.steer } else { self.normal }
    }
}

impl SessionController {
    pub(super) fn prompt_target(
        state: &ControllerState,
        generation: &str,
    ) -> Result<(Arc<omp::Runtime>, bool), CommandFailure> {
        let (_, runtime) = Self::matching_runtime(state, generation)?;
        Ok((runtime, route(state.projection.as_ref())?))
    }
}

/// Called under the controller state lock immediately before dispatch, including after startup.
pub(super) fn route(snapshot: Option<&RuntimeSnapshot>) -> Result<bool, CommandFailure> {
    let snapshot = snapshot
        .ok_or_else(|| CommandFailure::failed("runtime_required", "Runtime is unavailable"))?;
    if snapshot.phase != "ready" {
        return Err(CommandFailure::failed(
            "runtime_stopping",
            "Runtime is not accepting commands",
        ));
    }
    if !snapshot.pending_inputs.is_empty() {
        return Err(CommandFailure::failed(
            "input_pending",
            "Answer the pending input first",
        ));
    }
    match snapshot.execution.as_str() {
        "active" => Ok(true),
        "quiescent" => Ok(false),
        _ => Err(CommandFailure::failed(
            "runtime_not_ready",
            "Runtime is not ready",
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn admission_uses_state_after_preparation_and_preserves_route_specific_files() {
        let mut snapshot = RuntimeSnapshot {
            generation: "g".into(),
            phase: "ready".into(),
            execution: "active".into(),
            activity: None,
            actual_model: None,
            pending_inputs: vec![],
            work_timing: None,
        };
        let prepare = || {
            PreparedPrompt::new(
                "id",
                "hello",
                PromptFiles {
                    direct_note: " @file".into(),
                    queued_note: " path".into(),
                    images: vec![json!({"type":"image"})],
                },
            )
            .unwrap()
        };
        let prepared = prepare();
        snapshot.execution = "quiescent".into();
        let frame = prepared.frame(route(Some(&snapshot)).unwrap());
        assert_eq!(frame["message"], "hello @file");
        assert!(frame.get("images").is_none());
        assert!(frame.get("streamingBehavior").is_none());
        snapshot.execution = "active".into();
        let frame = prepare().frame(route(Some(&snapshot)).unwrap());
        assert_eq!(frame["message"], "hello path");
        assert_eq!(frame["streamingBehavior"], "steer");
        assert_eq!(frame["images"][0]["type"], "image");
        snapshot.pending_inputs.push(crate::model::Attention {
            id: "input".into(),
            kind: "confirm".into(),
            text: "Continue?".into(),
            options: vec![],
        });
        assert_eq!(route(Some(&snapshot)).unwrap_err().code, "input_pending");
        snapshot.phase = "stopping".into();
        assert_eq!(route(Some(&snapshot)).unwrap_err().code, "runtime_stopping");
        snapshot.phase = "ready".into();
        snapshot.pending_inputs.clear();
        snapshot.execution = "unknown".into();
        assert_eq!(
            route(Some(&snapshot)).unwrap_err().code,
            "runtime_not_ready"
        );
    }
}
