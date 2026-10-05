use super::*;

impl SessionController {
    pub(super) async fn apply_frame(self: &Arc<Self>, generation: &str, frame: Value) {
        // Answer-triggered frames follow durable feedback publication. Exit/Stop
        // bypass this gate, so a stalled writer cannot delay termination.
        let _feedback = self.feedback_order.lock().await;
        let kind = omp::string(&frame, "type").to_owned();
        if kind == "prompt_result" {
            let (session_id, key, status, error) = {
                let mut state = self.state.lock().await;
                if state
                    .runtime
                    .as_ref()
                    .is_none_or(|r| r.generation != generation)
                {
                    return;
                }
                let key = state
                    .pending_prompt_results
                    .remove(omp::string(&frame, "id"));
                if frame["sessionSettled"] == true
                    && let Some(snapshot) = state.projection.as_mut()
                {
                    snapshot.execution = "quiescent".into();
                    state.update_work_timing(false);
                    state.settled_revision = state.settled_revision.wrapping_add(1);
                }
                let status = match omp::string(&frame, "status") {
                    "completed" => "succeeded",
                    "aborted" => "cancelled",
                    "error" => "failed",
                    _ => "outcome_unknown",
                };
                self.publish_state(&mut state);
                (
                    state.session.id.clone(),
                    key,
                    status,
                    frame.get("error").cloned(),
                )
            };
            if let Some((client_id, command_id)) = key {
                let controller = self.clone();
                let generation = generation.to_owned();
                tokio::spawn(async move {
                    let read_session = session_id.clone();
                    let Ok(Some(mut receipt)) = controller
                        .store
                        .run(move |store| store.operation(&client_id, &read_session, &command_id))
                        .await
                    else {
                        return;
                    };
                    let _ = controller
                        .advance(
                            &mut receipt,
                            status,
                            Some(json!({"generation":generation})),
                            error,
                        )
                        .await;
                });
            }
            return;
        }
        let mut state = self.state.lock().await;
        if state
            .runtime
            .as_ref()
            .is_none_or(|r| r.generation != generation)
        {
            return;
        }
        let mut changed_item = None;
        let mut changed_runtime = false;
        match kind.as_str() {
            "agent_start" => {
                if let Some(snapshot) = state.projection.as_mut() {
                    snapshot.execution = "active".into();
                    changed_runtime = true;
                }
                state.update_work_timing(true);
            }
            "session_settled" => {
                if let Some(snapshot) = state.projection.as_mut() {
                    snapshot.execution = "quiescent".into();
                    changed_runtime = true;
                }
                state.settled_revision = state.settled_revision.wrapping_add(1);
            }
            "extension_ui_request" => {
                let method = omp::string(&frame, "method");
                if let Some(snapshot) = state.projection.as_mut() {
                    if ["select", "confirm", "input", "editor"].contains(&method) {
                        let request_id = omp::string(&frame, "id");
                        if !snapshot.pending_inputs.iter().any(|a| a.id == request_id) {
                            snapshot.pending_inputs.push(Attention {
                                id: request_id.into(),
                                kind: method.into(),
                                text: omp::string(&frame, "title").into(),
                                options: frame["options"]
                                    .as_array()
                                    .map(|items| {
                                        items
                                            .iter()
                                            .filter_map(Value::as_str)
                                            .map(str::to_owned)
                                            .collect()
                                    })
                                    .unwrap_or_default(),
                            });
                            changed_runtime = true;
                        }
                    } else if method == "cancel" {
                        let before = snapshot.pending_inputs.len();
                        snapshot
                            .pending_inputs
                            .retain(|a| a.id != omp::string(&frame, "targetId"));
                        changed_runtime = snapshot.pending_inputs.len() != before;
                    }
                }
            }
            "model_changed" => {
                let controller = self.clone();
                let generation = generation.to_owned();
                if let Some(runtime) = state.runtime.as_ref().map(|r| r.process.clone()) {
                    tokio::spawn(async move {
                        let _ = controller.refresh_state(&generation, &runtime).await;
                    });
                }
            }
            "tool_execution_start" | "tool_execution_update" | "tool_execution_end" => {
                let call_id = omp::string(&frame, "toolCallId");
                if !call_id.is_empty() {
                    let item_id = call_id.to_owned();
                    if kind == "tool_execution_update"
                        && state.messages.iter().any(|entry| {
                            entry.id == item_id
                                && entry.tool.as_ref().is_some_and(|tool| tool.completed)
                        })
                    {
                        return;
                    }
                    let name = omp::string(&frame, "toolName");
                    let item = if kind == "tool_execution_start" {
                        let args = frame.get("args").cloned().unwrap_or(Value::Null);
                        TimelineItem::tool_started(item_id.clone(), name, args, Utc::now())
                    } else if kind == "tool_execution_update" {
                        let mut item = TimelineItem::tool_started(
                            item_id.clone(),
                            name,
                            frame.get("args").cloned().unwrap_or(Value::Null),
                            Utc::now(),
                        );
                        item.tool.as_mut().unwrap().result =
                            omp::text_content(&frame["partialResult"]);
                        item
                    } else {
                        let result = omp::text_content(&frame["result"]);
                        TimelineItem::tool_completed(
                            item_id.clone(),
                            name,
                            result,
                            frame["isError"] == true,
                            Utc::now(),
                        )
                        .with_todo_details(&frame["result"]["details"])
                    };
                    if let Some(previous) =
                        state.messages.iter_mut().find(|entry| entry.id == item_id)
                    {
                        previous.merge_tool_update(item);
                    } else {
                        state.messages.push(item);
                    }
                    changed_item = Some(item_id);
                }
            }
            "message_start" if frame["message"]["role"] == "assistant" => {
                let engine_id = omp::string(&frame, "messageId");
                if !engine_id.is_empty() {
                    let id = format!("{generation}:{engine_id}");
                    if !state.messages.iter().any(|item| item.id == id) {
                        state.messages.push(TimelineItem {
                            id,
                            source_id: None,
                            message_key: crate::tool_details::message_key(&frame["message"]),
                            kind: "assistant".into(),
                            text: String::new(),
                            detail: "streaming".into(),
                            tool: None,
                            timestamp: Utc::now(),
                        });
                    }
                }
            }
            "message_update" if frame["assistantMessageEvent"]["type"] == "text_delta" => {
                let engine_id = omp::string(&frame, "messageId");
                if !engine_id.is_empty() {
                    let message_id = format!("{generation}:{engine_id}");
                    if !state.finalized_messages.contains(&message_id) {
                        changed_item = Some(message_id.clone());
                        let delta = omp::string(&frame["assistantMessageEvent"], "delta");
                        if let Some(item) =
                            state.messages.iter_mut().find(|item| item.id == message_id)
                        {
                            item.text.push_str(delta);
                        } else {
                            state.messages.push(TimelineItem {
                                id: message_id,
                                source_id: None,
                                message_key: crate::tool_details::message_key(&frame["message"]),
                                kind: "assistant".into(),
                                text: delta.into(),
                                detail: "streaming".into(),
                                tool: None,
                                timestamp: Utc::now(),
                            });
                        }
                    }
                }
            }
            "message_end"
                if frame["message"]["role"] == "assistant"
                    || frame["message"]["role"] == "user" =>
            {
                let engine_id = omp::string(&frame, "messageId");
                if !engine_id.is_empty() {
                    let message_id = format!("{generation}:{engine_id}");
                    let text = omp::text_content(&frame["message"]);
                    let kind = omp::string(&frame["message"], "role");
                    state.finalized_messages.insert(message_id.clone());
                    if !text.is_empty() {
                        changed_item = Some(message_id.clone());
                        let source_id = Some(crate::tool_details::message_source(
                            &frame["message"],
                            engine_id,
                        ));
                        if let Some(item) =
                            state.messages.iter_mut().find(|item| item.id == message_id)
                        {
                            item.text = text;
                            item.source_id = source_id;
                            item.message_key = crate::tool_details::message_key(&frame["message"]);
                            item.detail.clear();
                        } else {
                            state.messages.push(TimelineItem {
                                id: message_id,
                                source_id,
                                message_key: crate::tool_details::message_key(&frame["message"]),
                                kind: kind.into(),
                                text,
                                detail: String::new(),
                                tool: None,
                                timestamp: Utc::now(),
                            });
                        }
                    }
                }
            }
            _ => {}
        }
        let excess = state.messages.len().saturating_sub(LIVE_ITEM_LIMIT);
        if excess > 0 {
            let removed = state
                .messages
                .drain(..excess)
                .map(|item| item.id)
                .collect::<Vec<_>>();
            for id in removed {
                state.finalized_messages.remove(&id);
                state.dirty_messages.remove(&id);
                state.published_messages.remove(&id);
                state.removed_messages.push(id);
            }
        }
        if let Some(item) = changed_item
            && state.messages.iter().any(|message| message.id == item)
        {
            state.dirty_messages.insert(item);
        }
        if changed_runtime {
            state.update_work_timing(false);
            self.publish_state(&mut state);
        }
        let schedule_flush = !state.display_flush_scheduled
            && (!state.dirty_messages.is_empty() || !state.removed_messages.is_empty());
        if schedule_flush {
            state.display_flush_scheduled = true;
        }
        drop(state);
        if schedule_flush {
            let controller = self.clone();
            let generation = generation.to_owned();
            tokio::spawn(async move {
                tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                controller.flush_display(&generation).await;
            });
        }
    }
    async fn flush_display(&self, generation: &str) {
        // Keep publication ordered with exit/reset and concurrent timeline mutations.
        let mut state = self.state.lock().await;
        let (session_id, items, removed) = {
            if state
                .runtime
                .as_ref()
                .is_none_or(|r| r.generation != generation)
            {
                return;
            }
            state.display_flush_scheduled = false;
            let dirty = std::mem::take(&mut state.dirty_messages);
            let removed = std::mem::take(&mut state.removed_messages);
            let items = state
                .messages
                .iter()
                .filter(|item| dirty.contains(&item.id))
                .map(TimelineItem::summary)
                .collect::<Vec<_>>();
            (state.session.id.clone(), items, removed)
        };
        let mut replacements = Vec::new();
        for item in items {
            let previous = state
                .published_messages
                .insert(item.id.clone(), item.clone());
            if let Some(previous) = previous {
                if serde_json::to_value(&previous).ok() == serde_json::to_value(&item).ok() {
                    continue;
                }
                if item.tool.is_none() && item.text.starts_with(&previous.text) {
                    let mut metadata = item.clone();
                    metadata.text.clear();
                    let mut old_metadata = previous.clone();
                    old_metadata.text.clear();
                    self.bus.publish(ServerEvent::MessagePatch {
                        session_id: session_id.clone(),
                        id: item.id.clone(),
                        base_hash: hex::encode(Sha256::digest(previous.text.as_bytes())),
                        hash: hex::encode(Sha256::digest(item.text.as_bytes())),
                        append: item.text[previous.text.len()..].into(),
                        metadata: (serde_json::to_value(&metadata).ok()
                            != serde_json::to_value(&old_metadata).ok())
                        .then_some(metadata),
                    });
                    continue;
                }
            }
            replacements.push(item);
        }
        if !removed.is_empty() {
            self.bus.publish(ServerEvent::Timeline {
                session_id: session_id.clone(),
                upsert: vec![],
                remove: removed,
                reset: false,
            });
        }
        let mut chunk = Vec::new();
        let mut bytes = 0;
        for item in replacements {
            let item_bytes = serde_json::to_vec(&item).map_or(LIVE_PATCH_LIMIT, |v| v.len());
            if bytes + item_bytes > LIVE_PATCH_LIMIT && !chunk.is_empty() {
                self.bus.publish(ServerEvent::Timeline {
                    session_id: session_id.clone(),
                    upsert: chunk,
                    remove: vec![],
                    reset: false,
                });
                chunk = Vec::new();
                bytes = 0;
            }
            bytes += item_bytes;
            chunk.push(item);
        }
        if !chunk.is_empty() {
            self.bus.publish(ServerEvent::Timeline {
                session_id: session_id.clone(),
                upsert: chunk,
                remove: vec![],
                reset: false,
            });
        }
    }
    pub(super) async fn apply_exit(self: &Arc<Self>, generation: &str, _reason: Option<String>) {
        let mut state = self.state.lock().await;
        let session_id = {
            if state
                .runtime
                .as_ref()
                .is_none_or(|r| r.generation != generation)
            {
                return;
            }
            state.runtime = None;
            state.projection = None;
            state.work_clock = None;
            state.messages.clear();
            state.finalized_messages.clear();
            state.pending_prompt_results.clear();
            state.dirty_messages.clear();
            state.published_messages.clear();
            state.removed_messages.clear();
            state.display_flush_scheduled = false;
            self.publish_state(&mut state);
            self.bus.publish(ServerEvent::Timeline {
                session_id: state.session.id.clone(),
                upsert: vec![],
                remove: vec![],
                reset: true,
            });
            state.session.id.clone()
        };
        let cleanup_session = session_id.clone();
        let cleanup_generation = generation.to_owned();
        let cleanup = self
            .store
            .run(move |store| {
                let unknown = store.mark_generation_unknown(&cleanup_session, &cleanup_generation);
                let release = store.release_runtime(&cleanup_session, &cleanup_generation);
                Ok((unknown, release))
            })
            .await;
        if let Ok((Err(err), _)) = &cleanup {
            eprintln!("Could not mark exited runtime operations uncertain: {err}");
        }
        if let Ok((_, Err(err))) = cleanup {
            eprintln!("Could not release confirmed runtime lease: {err}");
        }
        if let Ok(operations) = self
            .store
            .run(move |store| store.recent_operations(&session_id, 20))
            .await
        {
            for receipt in operations.iter().filter(|o| {
                o.runtime_generation.as_deref() == Some(generation) && o.status == "outcome_unknown"
            }) {
                self.bus.publish(ServerEvent::Operation {
                    session_id: receipt.session_id.clone(),
                    operation: OperationDto::from(receipt),
                });
            }
        }
    }
    pub(super) async fn stop_on_shutdown(self: &Arc<Self>) {
        let current = {
            self.state
                .lock()
                .await
                .runtime
                .as_ref()
                .map(|r| (r.generation.clone(), r.process.clone()))
        };
        if let Some((generation, runtime)) = current
            && runtime.stop_confirmed().await.is_ok()
        {
            self.apply_exit(&generation, None).await;
        }
    }
}
