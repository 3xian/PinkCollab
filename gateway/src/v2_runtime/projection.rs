use super::*;

impl SessionController {
    pub(super) async fn apply_frame(self: &Arc<Self>, generation: &str, frame: Value) {
        let kind = omp::string(&frame, "type").to_owned();
        if kind == "prompt_result" {
            let (session_id, key, status, error, projection) = {
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
                    state.settled_revision = state.settled_revision.wrapping_add(1);
                }
                let status = match omp::string(&frame, "status") {
                    "completed" => "succeeded",
                    "aborted" => "cancelled",
                    "error" => "failed",
                    _ => "outcome_unknown",
                };
                (
                    state.session.id.clone(),
                    key,
                    status,
                    frame.get("error").cloned(),
                    state.projection.clone(),
                )
            };
            self.publish(
                &session_id,
                "v2.runtime.updated",
                json!({"runtime":projection}),
            );
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
                            Some(json!({"runtimeGeneration":generation})),
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
        let id = state.session.id.clone();
        let mut changed_item = None;
        let mut changed_runtime = false;
        match kind.as_str() {
            "agent_start" => {
                if let Some(snapshot) = state.projection.as_mut() {
                    snapshot.execution = "active".into();
                    changed_runtime = true;
                }
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
            "tool_execution_start" | "tool_execution_end" => {
                let call_id = omp::string(&frame, "toolCallId");
                if !call_id.is_empty() {
                    let item_id = format!("{generation}:{call_id}");
                    let name = omp::string(&frame, "toolName");
                    let item = if kind == "tool_execution_start" {
                        let args = frame.get("args").cloned().unwrap_or(Value::Null);
                        let args = if serde_json::to_vec(&args)
                            .is_ok_and(|bytes| bytes.len() <= LIVE_TEXT_LIMIT)
                        {
                            args
                        } else {
                            json!({"previewTruncated":true})
                        };
                        TimelineItem::tool_started(item_id.clone(), name, args, Utc::now())
                    } else {
                        let result = omp::text_content(&frame["result"]);
                        let (preview, _) = live_preview(&result);
                        TimelineItem::tool_completed(
                            item_id.clone(),
                            name,
                            preview,
                            frame["isError"] == true,
                            Utc::now(),
                        )
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
                            if item.detail != "preview_truncated" {
                                let (preview, truncated) =
                                    live_preview(&format!("{}{}", item.text, delta));
                                item.text = preview;
                                if truncated {
                                    item.detail = "preview_truncated".into();
                                }
                            }
                        } else {
                            let (preview, truncated) = live_preview(delta);
                            state.messages.push(TimelineItem {
                                id: message_id,
                                kind: "assistant".into(),
                                text: preview,
                                detail: if truncated {
                                    "preview_truncated".into()
                                } else {
                                    String::new()
                                },
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
                        let (preview, truncated) = live_preview(&text);
                        if let Some(item) =
                            state.messages.iter_mut().find(|item| item.id == message_id)
                        {
                            item.text = preview;
                            item.detail = if truncated {
                                "preview_truncated".into()
                            } else {
                                String::new()
                            };
                        } else {
                            state.messages.push(TimelineItem {
                                id: message_id,
                                kind: kind.into(),
                                text: preview,
                                detail: if truncated {
                                    "preview_truncated".into()
                                } else {
                                    String::new()
                                },
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
                state.removed_messages.push(id);
            }
        }
        if let Some(item) = changed_item
            && state.messages.iter().any(|message| message.id == item)
        {
            state.dirty_messages.insert(item);
        }
        if changed_runtime {
            self.publish(
                &id,
                "v2.runtime.updated",
                json!({"runtime":state.projection}),
            );
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
        let (session_id, items, removed) = {
            let mut state = self.state.lock().await;
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
                .cloned()
                .collect::<Vec<_>>();
            (state.session.id.clone(), items, removed)
        };
        if !removed.is_empty() {
            self.publish(
                &session_id,
                "v2.timeline.patch",
                json!({"items":[],"removedIds":removed}),
            );
        }
        let mut chunk = Vec::new();
        let mut bytes = 0;
        for item in items {
            let item_bytes = serde_json::to_vec(&item).map_or(LIVE_PATCH_LIMIT, |v| v.len());
            if bytes + item_bytes > LIVE_PATCH_LIMIT && !chunk.is_empty() {
                self.publish(
                    &session_id,
                    "v2.timeline.patch",
                    json!({"items":chunk,"removedIds":[]}),
                );
                chunk = Vec::new();
                bytes = 0;
            }
            bytes += item_bytes;
            chunk.push(item);
        }
        if !chunk.is_empty() {
            self.publish(
                &session_id,
                "v2.timeline.patch",
                json!({"items":chunk,"removedIds":[]}),
            );
        }
    }
    pub(super) async fn apply_exit(&self, generation: &str, reason: Option<String>) {
        let session_id = {
            let mut state = self.state.lock().await;
            if state
                .runtime
                .as_ref()
                .is_none_or(|r| r.generation != generation)
            {
                return;
            }
            state.runtime = None;
            state.projection = None;
            state.messages.clear();
            state.finalized_messages.clear();
            state.pending_prompt_results.clear();
            state.dirty_messages.clear();
            state.removed_messages.clear();
            state.display_flush_scheduled = false;
            state.session.id.clone()
        };
        self.publish(
            &session_id,
            "v2.runtime.exited",
            json!({"generation":generation,"reason":reason}),
        );
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
    }
    pub(super) async fn stop_on_shutdown(&self) {
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
