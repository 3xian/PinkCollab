use super::*;
use crate::history::FeedbackRecord;
use std::path::Path;

impl SessionController {
    pub(super) async fn deliver_response(
        &self,
        receipt: &mut OperationRecord,
        generation: &str,
        input_request_id: &str,
        value: Option<String>,
        confirmed: Option<bool>,
        cancelled: bool,
    ) -> std::result::Result<CommandResult, CommandFailure> {
        // The writer acknowledges transport delivery independently of apply_frame.
        // This gate orders answers and history, not process termination or snapshots.
        let _feedback = self.feedback_order.lock().await;
        let (attention, text, reference) = {
            let state = self.state.lock().await;
            let (_, attention, reference) = response_target(&state, generation, input_request_id)?;
            (
                attention.clone(),
                answer_text(attention, value.as_deref(), confirmed, cancelled)?,
                reference.to_owned(),
            )
        };
        self.bind_generation(receipt, generation).await?;
        let lookup_session = receipt.session_id.clone();
        let lookup_reference = reference.clone();
        let allow_missing = self
            .store
            .run(move |store| store.history_read_policy(&lookup_session, &lookup_reference, false))
            .await
            .map_err(|err| CommandFailure::failed("persistence_unavailable", format!("{err:#}")))?;
        let (anchor, file_exists) =
            crate::history::response_anchor(crate::history::HistorySource {
                path: Path::new(&reference),
                allow_missing,
            })
            .await
            .map_err(|err| CommandFailure::failed("history_unavailable", format!("{err:#}")))?;
        let mark_session = receipt.session_id.clone();
        let mark_reference = reference.clone();
        let marked = self
            .store
            .run(move |store| {
                store.prepare_response_history(&mark_session, &mark_reference, file_exists)
            })
            .await
            .map_err(|_| {
                CommandFailure::failed(
                    "persistence_unavailable",
                    "Could not protect the response history reference",
                )
            })?;
        if !marked {
            return Err(CommandFailure::failed(
                "history_unavailable",
                "Session history reference changed before response",
            ));
        }
        let mut frame = json!({"type":"extension_ui_response","id":attention.id});
        if cancelled {
            frame["cancelled"] = json!(true);
        } else if attention.kind == "confirm" {
            frame["confirmed"] = json!(confirmed);
        } else {
            frame["value"] = json!(value.unwrap_or_default());
        }
        // Revalidate after disk work; Stop may have changed or exited the generation.
        let runtime = {
            let state = self.state.lock().await;
            response_target(&state, generation, input_request_id)?.0
        };
        if let Err(err) = runtime.write(frame).await {
            return Err(self
                .uncertain_response(receipt, reference, err.to_string())
                .await);
        }
        let timestamp = Utc::now();
        let id = format!("feedback:{}", rpc_id(receipt));
        let item = TimelineItem {
            id: id.clone(),
            source_id: Some(id),
            message_key: None,
            kind: "feedback".into(),
            text,
            detail: attention.text,
            tool: None,
            timestamp,
        };
        // A successful write proves stdin delivery, not model acknowledgement. Consume the
        // attention only now; failed writes leave no feedback and no fictitious answer.
        {
            let mut state = self.state.lock().await;
            if state
                .runtime
                .as_ref()
                .is_some_and(|runtime| runtime.generation == generation)
            {
                if let Some(snapshot) = state.projection.as_mut() {
                    snapshot
                        .pending_inputs
                        .retain(|input| input.id != input_request_id);
                }
                state.update_work_timing(false);
                self.publish_state(&mut state);
            }
        }
        let pending_receipt = receipt.clone();
        let feedback = FeedbackRecord {
            reference: reference.clone(),
            anchor,
            sequence: 0,
            item: item.clone(),
        };
        let request_id = input_request_id.to_owned();
        let completed = self
            .store
            .run(move |store| store.complete_response(pending_receipt, feedback, &request_id))
            .await;
        let completed = match completed {
            Ok(Some(completed)) => completed,
            Ok(None) => {
                return Err(self
                    .uncertain_response(
                        receipt,
                        reference,
                        "Response was delivered, but its command receipt was unavailable".into(),
                    )
                    .await);
            }
            Err(err) => {
                return Err(self.uncertain_response(
                    receipt, reference,
                    format!("Response was delivered, but its durable record could not be saved: {err:#}"),
                ).await);
            }
        };
        *receipt = completed;
        let mut state = self.state.lock().await;
        // A delivered answer stays durable even if Stop completed during persistence,
        // but must never reintroduce live messages into an exited/new generation.
        if state
            .runtime
            .as_ref()
            .is_some_and(|runtime| runtime.generation == generation)
        {
            state.messages.push(item.clone());
            let excess = state.messages.len().saturating_sub(LIVE_ITEM_LIMIT);
            let removed: Vec<_> = state.messages.drain(..excess).map(|item| item.id).collect();
            for id in &removed {
                state.finalized_messages.remove(id);
                state.dirty_messages.remove(id);
                state.published_messages.remove(id);
            }
            state
                .published_messages
                .insert(item.id.clone(), item.clone());
            self.bus.publish(ServerEvent::Timeline {
                session_id: state.session.id.clone(),
                upsert: vec![item],
                remove: removed,
                reset: false,
            });
        }
        self.bus.publish(ServerEvent::Operation {
            session_id: receipt.session_id.clone(),
            operation: OperationDto::from(&*receipt),
        });
        // The successful receipt already contains the authoritative history record. In
        // particular, execute must not replace its private result with a second advance.
        Ok(CommandResult::Recorded)
    }

    async fn uncertain_response(
        &self,
        receipt: &OperationRecord,
        reference: String,
        message: String,
    ) -> CommandFailure {
        // Only durably successful feedback may keep an absent transcript readable.
        // An uncertain delivery could have caused OMP to write conversation content.
        let session_id = receipt.session_id.clone();
        let protected = self
            .store
            .run(move |store| store.mark_history_may_write(&session_id, &reference))
            .await;
        match protected {
            Ok(true) => CommandFailure::uncertain(message),
            _ => {
                CommandFailure::uncertain(format!("{message}; could not protect uncertain history"))
            }
        }
    }
}

fn response_target<'a>(
    state: &'a ControllerState,
    generation: &str,
    input_request_id: &str,
) -> std::result::Result<(Arc<Runtime>, &'a Attention, &'a str), CommandFailure> {
    let (_, runtime) = SessionController::matching_runtime(state, generation)?;
    let snapshot = state
        .projection
        .as_ref()
        .ok_or_else(|| CommandFailure::failed("runtime_required", "Runtime is unavailable"))?;
    if snapshot.phase != "ready" {
        return Err(CommandFailure::failed(
            "runtime_stopping",
            "Runtime is not accepting commands",
        ));
    }
    let attention = snapshot
        .pending_inputs
        .iter()
        .find(|input| input.id == input_request_id)
        .ok_or_else(|| {
            CommandFailure::failed("input_expired", "Input request is no longer pending")
        })?;
    let reference = state.session.engine_session_ref.as_deref().ok_or_else(|| {
        CommandFailure::failed(
            "history_unavailable",
            "Session history reference is unavailable",
        )
    })?;
    Ok((runtime, attention, reference))
}

fn answer_text(
    attention: &Attention,
    value: Option<&str>,
    confirmed: Option<bool>,
    cancelled: bool,
) -> std::result::Result<String, CommandFailure> {
    if cancelled {
        return Ok("Cancelled".into());
    }
    match attention.kind.as_str() {
        "select" => value
            .filter(|value| {
                attention
                    .options
                    .iter()
                    .any(|option| option.as_str() == *value)
            })
            .map(str::to_owned)
            .ok_or_else(|| {
                CommandFailure::failed("invalid_answer", "Answer must match a select option")
            }),
        "confirm" => confirmed
            .map(|confirmed| if confirmed { "Confirmed" } else { "Declined" }.to_owned())
            .ok_or_else(|| CommandFailure::failed("invalid_answer", "Confirmed boolean required")),
        "input" | "editor" => Ok(value.unwrap_or_default().to_owned()),
        _ => Err(CommandFailure::failed(
            "invalid_answer",
            "Unsupported input request",
        )),
    }
}
