use super::{RuntimeInstance, ServerEvent, SessionController, model_info};
use crate::{
    domain::RuntimeSnapshot,
    omp::{self, Output, Runtime},
    storage,
};
use anyhow::{Context, Result, ensure};
use serde_json::json;
use std::{path::Path, sync::Arc};

impl SessionController {
    pub(super) async fn ensure_runtime(self: &Arc<Self>) -> Result<(String, Arc<Runtime>)> {
        {
            let state = self.state.lock().await;
            if let Some(current) = state.runtime.as_ref() {
                ensure!(
                    current.process.alive(),
                    "previous runtime exit is not confirmed"
                );
                ensure!(
                    state
                        .projection
                        .as_ref()
                        .is_some_and(|p| p.phase == "ready"),
                    "runtime is not ready"
                );
                return Ok((current.generation.clone(), current.process.clone()));
            }
        }
        let permit = self
            .quota
            .clone()
            .try_acquire_owned()
            .context("OMP runtime limit reached")?;
        let session = { self.state.lock().await.session.clone() };
        let cwd = self.browser.validate(Path::new(&session.cwd))?;
        let lookup = session.id.clone();
        let expected_omp_id = self
            .store
            .run(move |store| store.adopted_omp_id(&lookup))
            .await?;
        if let Some(expected) = expected_omp_id.as_deref() {
            let source =
                crate::discovery::validate_mapping(&session, expected, self.browser.clone())
                    .await?;
            crate::discovery::ensure_quiet(&source.path).await?;
            crate::history::history_page(&source.path, &session.id, None, 1).await?;
        }
        let generation = storage::id("run_");
        let reserve_session = session.id.clone();
        let reserve_generation = generation.clone();
        ensure!(
            self.store
                .run(move |store| store.reserve_runtime(&reserve_session, &reserve_generation))
                .await?,
            "previous runtime exit is not confirmed"
        );
        let (runtime, mut output) = match Runtime::spawn(&self.executable, &self.args, &cwd) {
            Ok(started) => started,
            Err(err) => {
                let release_session = session.id.clone();
                let release_generation = generation.clone();
                let _ = self
                    .store
                    .run(move |store| store.release_runtime(&release_session, &release_generation))
                    .await;
                return Err(err);
            }
        };
        {
            let mut state = self.state.lock().await;
            ensure!(state.runtime.is_none(), "runtime started concurrently");
            state.runtime = Some(RuntimeInstance {
                generation: generation.clone(),
                process: runtime.clone(),
                _permit: permit,
            });
            state.projection = Some(RuntimeSnapshot {
                generation: generation.clone(),
                phase: "starting".into(),
                execution: "unknown".into(),
                activity: None,
                actual_model: None,
                pending_inputs: Vec::new(),
                work_timing: None,
            });
            state.work_clock = None;
            state.settled_revision = 0;
            state.messages.clear();
            state.finalized_messages.clear();
            state.dirty_messages.clear();
            state.removed_messages.clear();
            state.display_flush_scheduled = false;
        }
        {
            let mut state = self.state.lock().await;
            self.publish_state(&mut state);
            self.bus.publish(ServerEvent::Timeline {
                session_id: session.id.clone(),
                upsert: vec![],
                remove: vec![],
                reset: true,
            });
        }
        let controller = self.clone();
        let event_generation = generation.clone();
        tokio::spawn(async move {
            while let Some(event) = output.recv().await {
                match event {
                    Output::Frame(frame) => controller.apply_frame(&event_generation, frame).await,
                    Output::Exited(reason) => {
                        controller.apply_exit(&event_generation, reason).await;
                        break;
                    }
                }
            }
        });
        let started = async {
            runtime.wait_ready().await?;
            let missing_unwritten = if let Some(reference) = session.engine_session_ref.as_deref() {
                let missing = matches!(
                    std::fs::metadata(reference),
                    Err(err) if err.kind() == std::io::ErrorKind::NotFound
                );
                if missing {
                    let lookup_session = session.id.clone();
                    let lookup_reference = reference.to_owned();
                    self.store
                        .run(move |store| {
                            store.reference_is_unwritten(&lookup_session, &lookup_reference)
                        })
                        .await?
                } else {
                    false
                }
            } else {
                false
            };
            let mut startup_model = None;
            if let Some(reference) = session.engine_session_ref.as_deref()
                && !missing_unwritten
            {
                ensure!(
                    Path::new(reference).is_file(),
                    "stored OMP session is unavailable"
                );
                // Read OMP's configured default before switch_session restores the historical model.
                let initial = runtime.request(json!({"type":"get_state"})).await?;
                startup_model = Some(model_info(&initial["data"]).context("OMP default model unavailable")?);
                let response = runtime
                    .request(json!({"type":"switch_session","sessionPath":reference}))
                    .await?;
                ensure!(
                    response["data"]["cancelled"] != true,
                    "OMP did not load stored session"
                );
            }
            let mut response = runtime.request(json!({"type":"get_state"})).await?;
            ensure!(
                expected_omp_id
                    .as_deref()
                    .is_none_or(|expected| omp::string(&response["data"], "sessionId") == expected),
                "history_unavailable: OMP loaded a different session identity"
            );
            if let Some(default_model) = startup_model {
                let restored_model = model_info(&response["data"]);
                if restored_model.as_ref().is_none_or(|model| {
                    model.provider != default_model.provider || model.id != default_model.id
                }) {
                    runtime.request(json!({"type":"set_model","provider":default_model.provider,"modelId":default_model.id})).await?;
                    response = runtime.request(json!({"type":"get_state"})).await?;
                }
            }
            let data = &response["data"];
            ensure!(
                expected_omp_id
                    .as_deref()
                    .is_none_or(|expected| omp::string(data, "sessionId") == expected),
                "history_unavailable: OMP loaded a different session identity"
            );
            let reference = omp::string(data, "sessionFile");
            ensure!(
                !reference.is_empty(),
                "OMP did not provide a session reference"
            );
            if session.engine_session_ref.is_none() {
                let save_session = session.id.clone();
                let save_reference = reference.to_owned();
                let revision = session.metadata_revision;
                ensure!(
                    self.store
                        .run(move |store| store.set_engine_ref(
                            &save_session,
                            revision,
                            &save_reference
                        ))
                        .await?,
                    "session mapping changed during startup"
                );
            } else if session.engine_session_ref.as_deref() != Some(reference) {
                ensure!(missing_unwritten, "OMP loaded a different session");
                let replace_session = session.id.clone();
                let old_reference = session.engine_session_ref.as_deref().unwrap().to_owned();
                let new_reference = reference.to_owned();
                let revision = session.metadata_revision;
                ensure!(
                    self.store
                        .run(move |store| store.replace_unwritten_engine_ref(
                            &replace_session,
                            revision,
                            &old_reference,
                            &new_reference
                        ))
                        .await?,
                    "session mapping changed during startup"
                );
            }
            let reload_session = session.id.clone();
            let saved = self
                .store
                .run(move |store| store.v2_session(&reload_session))
                .await?
                .context("session disappeared during startup")?;
            let mut state = self.state.lock().await;
            ensure!(
                state
                    .runtime
                    .as_ref()
                    .is_some_and(|r| r.generation == generation),
                "runtime generation changed during startup"
            );
            state.session = saved;
            if let Some(snapshot) = state.projection.as_mut() {
                snapshot.phase = "ready".into();
                snapshot.execution = if data["isSettled"] == true {
                    "quiescent"
                } else if data["isStreaming"] == true {
                    "active"
                } else {
                    "unknown"
                }
                .into();
                snapshot.actual_model = model_info(data);
            }
            state.update_work_timing(false);
            Ok::<(), anyhow::Error>(())
        }
        .await;
        if let Err(err) = started {
            let stopped = runtime.stop_confirmed().await;
            if stopped.is_ok() {
                self.apply_exit(&generation, Some(err.to_string())).await;
            }
            return Err(err);
        }
        {
            let mut state = self.state.lock().await;
            self.publish_state(&mut state);
        }
        Ok((generation, runtime))
    }
}
