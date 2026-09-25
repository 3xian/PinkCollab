use crate::{
    events::Bus,
    model::{Attention, ModelInfo, TimelineItem},
    omp::{self, Output, Runtime},
    storage::{self, Store},
    uploads,
    v2_model::{OperationRecord, RuntimeSnapshot, SessionRecord, SessionView},
    workspace::Browser,
};
use anyhow::{Context, Result, ensure};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    path::Path,
    sync::Arc,
};
use tokio::sync::{Mutex, OwnedSemaphorePermit, Semaphore};

const LIVE_ITEM_LIMIT: usize = 64;
const LIVE_TEXT_LIMIT: usize = 8 * 1024;
const LIVE_PATCH_LIMIT: usize = 128 * 1024;
const PREVIEW_NOTICE: &str = "\n\n[Live preview truncated; full message remains in OMP history]";

fn live_preview(value: &str) -> (String, bool) {
    if value.len() <= LIVE_TEXT_LIMIT {
        return (value.into(), false);
    }
    let end = value
        .char_indices()
        .map(|(index, _)| index)
        .take_while(|index| *index <= LIVE_TEXT_LIMIT)
        .last()
        .unwrap_or(0);
    (format!("{}{}", &value[..end], PREVIEW_NOTICE), true)
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Delivery {
    Start,
    Steer,
    FollowUp,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "snake_case",
    rename_all_fields = "camelCase"
)]
pub enum Command {
    StartRuntime,
    Prompt {
        delivery: Delivery,
        message: String,
        #[serde(default)]
        file_ids: Vec<String>,
        #[serde(default)]
        expected_generation: Option<String>,
    },
    Interrupt {
        expected_generation: String,
    },
    StopRuntime {
        expected_generation: String,
    },
    Respond {
        expected_generation: String,
        input_request_id: String,
        #[serde(default)]
        value: Option<String>,
        #[serde(default)]
        confirmed: Option<bool>,
        #[serde(default)]
        cancelled: bool,
    },
    SelectModel {
        expected_generation: String,
        provider: String,
        model_id: String,
    },
    SetThinkingLevel {
        expected_generation: String,
        level: String,
    },
}

impl Command {
    fn name(&self) -> &'static str {
        match self {
            Self::StartRuntime => "start_runtime",
            Self::Prompt { .. } => "prompt",
            Self::Interrupt { .. } => "interrupt",
            Self::StopRuntime { .. } => "stop_runtime",
            Self::Respond { .. } => "respond",
            Self::SelectModel { .. } => "select_model",
            Self::SetThinkingLevel { .. } => "set_thinking_level",
        }
    }
    fn validate(&self) -> Result<()> {
        match self {
            Self::Prompt {
                delivery,
                message,
                file_ids,
                expected_generation,
            } => {
                ensure!(
                    (!message.trim().is_empty() || !file_ids.is_empty()) && message.len() <= 262144,
                    "message or files required; message must be at most 262144 bytes"
                );
                ensure!(
                    file_ids.len() <= uploads::MAX_FILES_PER_PROMPT
                        && file_ids.iter().all(|id| uploads::valid_file_id(id)),
                    "Invalid file IDs"
                );
                if !matches!(delivery, Delivery::Start) {
                    ensure!(
                        expected_generation.as_ref().is_some_and(|s| !s.is_empty()),
                        "expectedGeneration required"
                    );
                }
            }
            Self::Interrupt {
                expected_generation,
            }
            | Self::StopRuntime {
                expected_generation,
            } => ensure!(
                !expected_generation.is_empty(),
                "expectedGeneration required"
            ),
            Self::Respond {
                expected_generation,
                input_request_id,
                value,
                ..
            } => {
                ensure!(
                    !expected_generation.is_empty() && !input_request_id.is_empty(),
                    "generation and input request required"
                );
                ensure!(
                    value.as_ref().is_none_or(|value| value.len() <= 262144),
                    "answer too large"
                );
            }
            Self::SelectModel {
                expected_generation,
                provider,
                model_id,
            } => ensure!(
                !expected_generation.is_empty() && !provider.is_empty() && !model_id.is_empty(),
                "generation, provider and model required"
            ),
            Self::SetThinkingLevel {
                expected_generation,
                level,
            } => ensure!(
                !expected_generation.is_empty() && !level.is_empty(),
                "generation and level required"
            ),
            Self::StartRuntime => {}
        }
        Ok(())
    }
}

pub enum SubmitError {
    NotFound,
    Invalid(String),
    Persistence,
    Conflict,
}
pub struct Submitted {
    pub receipt: OperationRecord,
    pub replayed: bool,
    pub receipt_stored: bool,
}

struct RuntimeInstance {
    generation: String,
    process: Arc<Runtime>,
    // The permit leaves only after a confirmed process exit, including a forced reap.
    _permit: OwnedSemaphorePermit,
}
struct ControllerState {
    session: SessionRecord,
    runtime: Option<RuntimeInstance>,
    projection: Option<RuntimeSnapshot>,
    messages: Vec<TimelineItem>,
    finalized_messages: HashSet<String>,
    pending_prompt_results: HashMap<String, (String, String)>,
    settled_revision: u64,
    dirty_messages: HashSet<String>,
    removed_messages: Vec<String>,
    display_flush_scheduled: bool,
}
pub struct SessionController {
    state: Mutex<ControllerState>,
    ordinary_dispatch: Mutex<()>,
    store: Arc<Store>,
    browser: Arc<Browser>,
    bus: Arc<Bus>,
    quota: Arc<Semaphore>,
    executable: String,
    args: Vec<String>,
}
pub struct SessionDirectory {
    controllers: Mutex<HashMap<String, std::sync::Weak<SessionController>>>,
    store: Arc<Store>,
    browser: Arc<Browser>,
    bus: Arc<Bus>,
    quota: Arc<Semaphore>,
    executable: String,
    args: Vec<String>,
}

mod directory;

enum CommandResult {
    Running,
    Succeeded(Value),
}
struct CommandFailure {
    code: &'static str,
    message: String,
    uncertain: bool,
}
impl CommandFailure {
    fn failed(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            uncertain: false,
        }
    }
    fn uncertain(message: impl Into<String>) -> Self {
        Self {
            code: "outcome_unknown",
            message: message.into(),
            uncertain: true,
        }
    }
}
impl SessionController {
    async fn view(&self) -> Result<SessionView> {
        let state = self.state.lock().await;
        let session = state.session.clone();
        let runtime = state.projection.clone();
        let messages = state.messages.clone();
        drop(state);
        let session_id = session.id.clone();
        let recent_operations = self
            .store
            .run(move |store| store.recent_operations(&session_id, 20))
            .await?;
        let history_ref = session
            .engine_session_ref
            .as_ref()
            .map(|_| "omp".to_string());
        Ok(SessionView {
            session,
            runtime,
            recent_operations,
            messages,
            history_ref,
        })
    }
    fn publish(&self, id: &str, kind: &str, payload: Value) {
        self.bus.publish_resource(
            &format!("session/{id}"),
            json!([{"type":kind,"value":payload}]),
        );
        if kind.starts_with("v2.runtime") || kind == "v2.metadata.updated" {
            self.bus.publish_resource("host/sessions",json!([{"type":"summary.changed","changeKind":kind,"sessionId":id,"value":payload}]));
        }
    }
    async fn persist_receipt(&self, receipt: &OperationRecord) -> Result<bool> {
        let receipt = receipt.clone();
        self.store
            .run(move |store| store.update_operation(&receipt))
            .await
    }
    async fn advance(
        &self,
        receipt: &mut OperationRecord,
        status: &str,
        result: Option<Value>,
        error: Option<Value>,
    ) -> Result<()> {
        receipt.status = status.into();
        receipt.result = result;
        receipt.error = error;
        receipt.updated_at = Utc::now();
        if self.persist_receipt(receipt).await? {
            self.publish(&receipt.session_id, "v2.operation.updated", json!(receipt));
        }
        Ok(())
    }
    async fn execute(self: Arc<Self>, mut receipt: OperationRecord, command: Command, stop: bool) {
        // Stop is deliberately outside the ordinary dispatch lock. A hung prompt write or RPC
        // response cannot prevent the process from being terminated.
        let control = matches!(command, Command::Interrupt { .. } | Command::Respond { .. });
        let _guard = if stop || control {
            None
        } else {
            Some(self.ordinary_dispatch.lock().await)
        };
        let stored = self
            .advance(&mut receipt, "dispatching", None, None)
            .await
            .is_ok();
        if !stored && !stop {
            return;
        }
        let result = self.run(&mut receipt, command).await;
        if !stored {
            return;
        }
        match result {
            Ok(CommandResult::Running) => {
                let _ = self.advance(&mut receipt, "running", None, None).await;
            }
            Ok(CommandResult::Succeeded(value)) => {
                let _ = self
                    .advance(&mut receipt, "succeeded", Some(value), None)
                    .await;
            }
            Err(err) => {
                let status = if err.uncertain {
                    "outcome_unknown"
                } else {
                    "failed"
                };
                let _ = self
                    .advance(
                        &mut receipt,
                        status,
                        None,
                        Some(json!({"code":err.code,"message":err.message})),
                    )
                    .await;
            }
        }
    }
    async fn run(
        self: &Arc<Self>,
        receipt: &mut OperationRecord,
        command: Command,
    ) -> std::result::Result<CommandResult, CommandFailure> {
        match command {
            Command::StartRuntime => {
                let (generation, _) = self.ensure_runtime().await.map_err(start_failure)?;
                self.bind_generation(receipt, &generation).await?;
                Ok(CommandResult::Succeeded(
                    json!({"runtimeGeneration":generation}),
                ))
            }
            Command::Prompt {
                delivery,
                message,
                file_ids,
                expected_generation,
            } => {
                let store = self.store.clone();
                let session_id = receipt.session_id.clone();
                let file_mode = match delivery {
                    Delivery::Start => uploads::PromptFileMode::Direct,
                    Delivery::Steer | Delivery::FollowUp => uploads::PromptFileMode::Queued,
                };
                let (file_note, images) = tokio::task::spawn_blocking(move || {
                    uploads::prompt_files(&store, &session_id, &file_ids, file_mode)
                })
                .await
                .map_err(|err| CommandFailure::failed("invalid_file", err.to_string()))?
                .map_err(|err| CommandFailure::failed("invalid_file", err.to_string()))?;
                let rpc_id = rpc_id(receipt);
                let mut frame =
                    json!({"type":"prompt","message":format!("{message}{file_note}"),"id":rpc_id});
                if !images.is_empty() {
                    frame["images"] = json!(images);
                }
                match delivery {
                    Delivery::Start => {}
                    Delivery::Steer => frame["streamingBehavior"] = json!("steer"),
                    Delivery::FollowUp => frame["streamingBehavior"] = json!("followUp"),
                };
                if serde_json::to_vec(&frame).map_or(true, |bytes| bytes.len() >= omp::MAX_LINE) {
                    return Err(CommandFailure::failed(
                        "invalid_request",
                        "Prompt and attachments exceed OMP input frame limit",
                    ));
                }
                let (generation, runtime) = match delivery {
                    Delivery::Start => self.ensure_runtime().await.map_err(start_failure)?,
                    _ => {
                        self.dispatchable_runtime(
                            expected_generation.as_deref().unwrap_or_default(),
                        )
                        .await?
                    }
                };
                self.bind_generation(receipt, &generation).await?;
                {
                    let state = self.state.lock().await;
                    let snapshot = state.projection.as_ref().ok_or_else(|| {
                        CommandFailure::failed("runtime_required", "Runtime is unavailable")
                    })?;
                    if !matches!(delivery, Delivery::Start) && snapshot.execution != "active" {
                        return Err(CommandFailure::failed(
                            "session_not_busy",
                            "No active execution to steer",
                        ));
                    }
                    if matches!(delivery, Delivery::Start) && snapshot.execution != "quiescent" {
                        return Err(CommandFailure::failed(
                            "session_busy",
                            "Session is not settled",
                        ));
                    }
                    if !snapshot.pending_inputs.is_empty() {
                        return Err(CommandFailure::failed(
                            "input_pending",
                            "Answer the pending input first",
                        ));
                    }
                }
                let settled_revision = {
                    let mut state = self.state.lock().await;
                    state.pending_prompt_results.insert(
                        rpc_id.clone(),
                        (receipt.client_id.clone(), receipt.command_id.clone()),
                    );
                    state.settled_revision
                };
                let response = runtime.request_with_id(rpc_id.clone(), frame).await;
                match response {
                    Ok(response) => {
                        if response["data"]["agentInvoked"] == false {
                            self.state
                                .lock()
                                .await
                                .pending_prompt_results
                                .remove(&rpc_id);
                            return Ok(CommandResult::Succeeded(
                                json!({"runtimeGeneration":generation,"agentInvoked":false}),
                            ));
                        }
                        self.set_prompt_active(&generation, &rpc_id, settled_revision)
                            .await;
                        Ok(CommandResult::Running)
                    }
                    Err(err) if err.to_string().starts_with("OMP rejected command") => {
                        self.state
                            .lock()
                            .await
                            .pending_prompt_results
                            .remove(&rpc_id);
                        Err(CommandFailure::failed("omp_rejected", err.to_string()))
                    }
                    Err(err) => Err(CommandFailure::uncertain(err.to_string())),
                }
            }
            Command::StopRuntime {
                expected_generation,
            } => {
                let runtime = self.begin_stop(&expected_generation).await?;
                receipt.runtime_generation = Some(expected_generation.clone());
                {
                    let state = self.state.lock().await;
                    self.publish(
                        &receipt.session_id,
                        "v2.runtime.updated",
                        json!({"runtime":state.projection}),
                    );
                }
                runtime
                    .stop_confirmed()
                    .await
                    .map_err(|err| CommandFailure::uncertain(err.to_string()))?;
                self.apply_exit(&expected_generation, None).await;
                Ok(CommandResult::Succeeded(
                    json!({"stoppedGeneration":expected_generation}),
                ))
            }
            Command::Interrupt {
                expected_generation,
            } => {
                let (_, runtime) = self.dispatchable_runtime(&expected_generation).await?;
                self.bind_generation(receipt, &expected_generation).await?;
                runtime
                    .request(json!({"type":"abort"}))
                    .await
                    .map_err(rpc_failure)?;
                Ok(CommandResult::Succeeded(
                    json!({"runtimeGeneration":expected_generation}),
                ))
            }
            Command::Respond {
                expected_generation,
                input_request_id,
                value,
                confirmed,
                cancelled,
            } => {
                let (_, runtime) = self.dispatchable_runtime(&expected_generation).await?;
                self.bind_generation(receipt, &expected_generation).await?;
                let attention = {
                    let mut state = self.state.lock().await;
                    let snapshot = state.projection.as_mut().ok_or_else(|| {
                        CommandFailure::failed("runtime_required", "Runtime is unavailable")
                    })?;
                    let Some(index) = snapshot
                        .pending_inputs
                        .iter()
                        .position(|input| input.id == input_request_id)
                    else {
                        return Err(CommandFailure::failed(
                            "input_expired",
                            "Input request is no longer pending",
                        ));
                    };
                    let attention = snapshot.pending_inputs[index].clone();
                    if attention.kind == "select"
                        && !cancelled
                        && !value
                            .as_ref()
                            .is_some_and(|value| attention.options.contains(value))
                    {
                        return Err(CommandFailure::failed(
                            "invalid_answer",
                            "Answer must match a select option",
                        ));
                    }
                    if attention.kind == "confirm" && !cancelled && confirmed.is_none() {
                        return Err(CommandFailure::failed(
                            "invalid_answer",
                            "Confirmed boolean required",
                        ));
                    }
                    snapshot.pending_inputs.remove(index);
                    attention
                };
                {
                    let state = self.state.lock().await;
                    self.publish(
                        &receipt.session_id,
                        "v2.runtime.updated",
                        json!({"runtime":state.projection}),
                    );
                }
                let mut frame = json!({"type":"extension_ui_response","id":attention.id});
                if cancelled {
                    frame["cancelled"] = json!(true)
                } else if attention.kind == "confirm" {
                    frame["confirmed"] = json!(confirmed)
                } else {
                    frame["value"] = json!(value.unwrap_or_default())
                };
                runtime
                    .write(frame)
                    .await
                    .map_err(|err| CommandFailure::uncertain(err.to_string()))?;
                Ok(CommandResult::Succeeded(
                    json!({"inputRequestId":input_request_id}),
                ))
            }
            Command::SelectModel {
                expected_generation,
                provider,
                model_id,
            } => {
                let (_, runtime) = self.dispatchable_runtime(&expected_generation).await?;
                self.bind_generation(receipt, &expected_generation).await?;
                runtime
                    .request(json!({"type":"set_model","provider":provider,"modelId":model_id}))
                    .await
                    .map_err(rpc_failure)?;
                self.refresh_state(&expected_generation, &runtime)
                    .await
                    .map_err(|err| {
                        CommandFailure::uncertain(format!("Model set; state refresh failed: {err}"))
                    })?;
                Ok(CommandResult::Succeeded(
                    json!({"runtimeGeneration":expected_generation}),
                ))
            }
            Command::SetThinkingLevel {
                expected_generation,
                level,
            } => {
                let (_, runtime) = self.dispatchable_runtime(&expected_generation).await?;
                self.bind_generation(receipt, &expected_generation).await?;
                let available = runtime
                    .request(json!({"type":"get_available_thinking_levels"}))
                    .await
                    .map_err(rpc_failure)?;
                let levels = available["data"]["levels"].as_array().ok_or_else(|| {
                    CommandFailure::failed(
                        "unsupported_capability",
                        "OMP did not return thinking levels",
                    )
                })?;
                if !levels.iter().any(|candidate| {
                    candidate.as_str() == Some(level.as_str())
                        || candidate["id"].as_str() == Some(level.as_str())
                }) {
                    return Err(CommandFailure::failed(
                        "unsupported_capability",
                        "Thinking level is unavailable",
                    ));
                }
                runtime
                    .request(json!({"type":"set_thinking_level","level":level}))
                    .await
                    .map_err(rpc_failure)?;
                self.refresh_state(&expected_generation, &runtime)
                    .await
                    .map_err(|err| {
                        CommandFailure::uncertain(format!(
                            "Thinking level set; state refresh failed: {err}"
                        ))
                    })?;
                Ok(CommandResult::Succeeded(
                    json!({"runtimeGeneration":expected_generation}),
                ))
            }
        }
    }
    async fn bind_generation(
        &self,
        receipt: &mut OperationRecord,
        generation: &str,
    ) -> std::result::Result<(), CommandFailure> {
        receipt.runtime_generation = Some(generation.into());
        let bound_receipt = receipt.clone();
        let bound_generation = generation.to_owned();
        if !self
            .store
            .run(move |store| store.bind_operation_generation(&bound_receipt, &bound_generation))
            .await
            .map_err(|_| {
                CommandFailure::failed("persistence_unavailable", "Could not save runtime binding")
            })?
        {
            return Err(CommandFailure::failed(
                "persistence_unavailable",
                "Command receipt was not available for runtime binding",
            ));
        }
        Ok(())
    }
    fn matching_runtime(
        state: &ControllerState,
        expected: &str,
    ) -> std::result::Result<(String, Arc<Runtime>), CommandFailure> {
        let current = state
            .runtime
            .as_ref()
            .ok_or_else(|| CommandFailure::failed("runtime_required", "No attached runtime"))?;
        if current.generation != expected {
            return Err(CommandFailure::failed(
                "stale_runtime",
                "Runtime generation changed",
            ));
        }
        if !current.process.alive() {
            return Err(CommandFailure::failed(
                "runtime_required",
                "Runtime has exited",
            ));
        }
        Ok((current.generation.clone(), current.process.clone()))
    }
    async fn dispatchable_runtime(
        &self,
        expected: &str,
    ) -> std::result::Result<(String, Arc<Runtime>), CommandFailure> {
        let state = self.state.lock().await;
        let current = Self::matching_runtime(&state, expected)?;
        if state.projection.as_ref().is_none_or(|p| p.phase != "ready") {
            return Err(CommandFailure::failed(
                "runtime_stopping",
                "Runtime is not accepting commands",
            ));
        }
        Ok(current)
    }
    async fn begin_stop(
        &self,
        expected: &str,
    ) -> std::result::Result<Arc<Runtime>, CommandFailure> {
        let mut state = self.state.lock().await;
        let (_, runtime) = Self::matching_runtime(&state, expected)?;
        if let Some(snapshot) = state.projection.as_mut() {
            snapshot.phase = "stopping".into();
        }
        Ok(runtime)
    }
    async fn ensure_runtime(self: &Arc<Self>) -> Result<(String, Arc<Runtime>)> {
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
            });
            state.settled_revision = 0;
            state.messages.clear();
            state.finalized_messages.clear();
            state.dirty_messages.clear();
            state.removed_messages.clear();
            state.display_flush_scheduled = false;
        }
        {
            let state = self.state.lock().await;
            self.publish(
                &session.id,
                "v2.runtime.updated",
                json!({"runtime":state.projection}),
            );
            self.publish(&session.id, "v2.timeline.reset", json!({}));
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
            if let Some(reference) = session.engine_session_ref.as_deref() {
                ensure!(
                    Path::new(reference).is_file(),
                    "stored OMP session is unavailable"
                );
                let response = runtime
                    .request(json!({"type":"switch_session","sessionPath":reference}))
                    .await?;
                ensure!(
                    response["data"]["cancelled"] != true,
                    "OMP did not load stored session"
                );
            }
            let response = runtime.request(json!({"type":"get_state"})).await?;
            let data = &response["data"];
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
            } else {
                ensure!(
                    session.engine_session_ref.as_deref() == Some(reference),
                    "OMP loaded a different session"
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
            let state = self.state.lock().await;
            self.publish(
                &session.id,
                "v2.metadata.updated",
                json!({"session":state.session}),
            );
        }
        {
            let state = self.state.lock().await;
            self.publish(
                &session.id,
                "v2.runtime.updated",
                json!({"runtime":state.projection}),
            );
        }
        Ok((generation, runtime))
    }
    async fn refresh_state(&self, generation: &str, runtime: &Runtime) -> Result<()> {
        let response = runtime.request(json!({"type":"get_state"})).await?;
        let data = &response["data"];
        let mut state = self.state.lock().await;
        if state
            .runtime
            .as_ref()
            .is_none_or(|r| r.generation != generation)
        {
            return Ok(());
        }
        if let Some(snapshot) = state.projection.as_mut() {
            snapshot.actual_model = model_info(data);
            snapshot.execution = if data["isSettled"] == true {
                "quiescent"
            } else if data["isStreaming"] == true {
                "active"
            } else {
                "unknown"
            }
            .into();
        }
        self.publish(
            &state.session.id,
            "v2.runtime.updated",
            json!({"runtime":state.projection}),
        );
        Ok(())
    }
    async fn set_prompt_active(&self, generation: &str, rpc_id: &str, settled_revision: u64) {
        let mut state = self.state.lock().await;
        if state
            .runtime
            .as_ref()
            .is_some_and(|r| r.generation == generation)
            && state.settled_revision == settled_revision
            && state.pending_prompt_results.contains_key(rpc_id)
        {
            if let Some(snapshot) = state.projection.as_mut() {
                snapshot.execution = "active".into();
                snapshot.activity = None;
            }
            let id = state.session.id.clone();
            self.publish(
                &id,
                "v2.runtime.updated",
                json!({"runtime":state.projection}),
            );
        }
    }
}
mod projection;

fn rpc_id(receipt: &OperationRecord) -> String {
    let raw = format!(
        "{}:{}:{}",
        receipt.client_id, receipt.session_id, receipt.command_id
    );
    format!("pc_{}", hex::encode(Sha256::digest(raw.as_bytes())))
}
fn model_info(data: &Value) -> Option<ModelInfo> {
    let model = &data["model"];
    Some(ModelInfo {
        provider: omp::string(model, "provider").into(),
        id: omp::string(model, "id").into(),
        name: omp::string(model, "name").into(),
        thinking_level: data["thinkingLevel"].as_str().map(str::to_owned),
    })
    .filter(|model| !model.provider.is_empty() && !model.id.is_empty())
}
fn start_failure(err: anyhow::Error) -> CommandFailure {
    let message = format!("{err:#}");
    let code = if message.contains("limit") {
        "runtime_limit"
    } else if message.contains("stored OMP session") {
        "history_unavailable"
    } else {
        "runtime_start_failed"
    };
    CommandFailure::failed(code, message)
}
fn rpc_failure(err: anyhow::Error) -> CommandFailure {
    if err.to_string().starts_with("OMP rejected command") {
        CommandFailure::failed("omp_rejected", err.to_string())
    } else {
        CommandFailure::uncertain(err.to_string())
    }
}
