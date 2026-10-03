use crate::{
    domain::{OperationRecord, RuntimeSnapshot, SessionRecord, SessionView},
    events::Bus,
    model::{Attention, ModelInfo, TimelineItem},
    omp::{self, Runtime},
    protocol::{OperationDto, RuntimeDto, ServerEvent, SessionDto, SessionSummary},
    storage::Store,
    uploads,
    workspace::Browser,
};
use anyhow::{Result, ensure};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    sync::Arc,
};
use tokio::sync::{Mutex, OwnedMutexGuard, OwnedSemaphorePermit, Semaphore};

const LIVE_ITEM_LIMIT: usize = 64;
const LIVE_PATCH_LIMIT: usize = 128 * 1024;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "snake_case",
    rename_all_fields = "camelCase"
)]
pub enum Command {
    StartRuntime,
    Prompt {
        message: String,
        #[serde(default)]
        file_ids: Vec<String>,
        #[serde(default)]
        generation: Option<String>,
    },
    Interrupt {
        generation: String,
    },
    StopRuntime {
        generation: String,
    },
    Respond {
        generation: String,
        input_request_id: String,
        #[serde(default)]
        value: Option<String>,
        #[serde(default)]
        confirmed: Option<bool>,
        #[serde(default)]
        cancelled: bool,
    },
    SelectModel {
        generation: String,
        provider: String,
        model_id: String,
    },
    SetThinkingLevel {
        generation: String,
        level: String,
    },
    SetFastMode {
        generation: String,
        enabled: bool,
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
            Self::SetFastMode { .. } => "set_fast_mode",
        }
    }
    fn validate(&self) -> Result<()> {
        match self {
            Self::Prompt {
                message, file_ids, ..
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
            }
            Self::Interrupt { generation }
            | Self::StopRuntime { generation }
            | Self::SetFastMode { generation, .. } => {
                ensure!(!generation.is_empty(), "generation required")
            }
            Self::Respond {
                generation,
                input_request_id,
                value,
                ..
            } => {
                ensure!(
                    !generation.is_empty() && !input_request_id.is_empty(),
                    "generation and input request required"
                );
                ensure!(
                    value.as_ref().is_none_or(|value| value.len() <= 262144),
                    "answer too large"
                );
            }
            Self::SelectModel {
                generation,
                provider,
                model_id,
            } => ensure!(
                !generation.is_empty() && !provider.is_empty() && !model_id.is_empty(),
                "generation, provider and model required"
            ),
            Self::SetThinkingLevel { generation, level } => ensure!(
                !generation.is_empty() && !level.is_empty(),
                "generation and level required"
            ),
            Self::StartRuntime => {}
        }
        Ok(())
    }
}

pub enum SubmitError {
    HistoryUnavailable,
    ExternalBusy,
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
    work_clock: Option<work_timing::WorkClock>,
    messages: Vec<TimelineItem>,
    finalized_messages: HashSet<String>,
    pending_prompt_results: HashMap<String, (String, String)>,
    settled_revision: u64,
    dirty_messages: HashSet<String>,
    published_messages: HashMap<String, TimelineItem>,
    removed_messages: Vec<String>,
    display_flush_scheduled: bool,
}
pub struct SessionController {
    state: Mutex<ControllerState>,
    ordinary_dispatch: Mutex<()>,
    prompt_interrupt_admission: Arc<Mutex<()>>,
    store: Arc<Store>,
    browser: Arc<Browser>,
    bus: Arc<Bus>,
    quota: Arc<Semaphore>,
    executable: String,
    args: Vec<String>,
}
pub struct SessionDirectory {
    adoption: Mutex<()>,
    discovery: Arc<Mutex<crate::discovery::Discovery>>,
    controllers: Mutex<HashMap<String, std::sync::Weak<SessionController>>>,
    store: Arc<Store>,
    browser: Arc<Browser>,
    bus: Arc<Bus>,
    quota: Arc<Semaphore>,
    executable: String,
    args: Vec<String>,
}

mod directory;
mod settings;
mod startup;
mod title;
mod work_timing;

enum CommandResult {
    Running,
    Succeeded(Value),
}
#[derive(Debug)]
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
        let mut state = self.state.lock().await;
        let session = state.session.clone();
        let runtime = state.capture_runtime().clone();
        let messages = state.messages.clone();
        let session_id = session.id.clone();
        let recent_operations = self
            .store
            .run(move |store| store.recent_operations(&session_id, 20))
            .await?;
        drop(state);
        let history_ref = session
            .engine_session_ref
            .as_ref()
            .map(|_| "omp".to_string());
        Ok(SessionView {
            origin: crate::protocol::SessionOrigin::Managed,
            session,
            runtime,
            recent_operations,
            messages,
            history_ref,
        })
    }
    fn publish_state(&self, state: &mut ControllerState) {
        let runtime = state.capture_runtime().as_ref().map(RuntimeDto::from);
        self.bus.publish(ServerEvent::SessionState {
            session_id: state.session.id.clone(),
            summary: SessionSummary {
                session: SessionDto::from(&state.session),
                runtime,
            },
            has_history: state.session.engine_session_ref.is_some(),
        });
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
        let _state = self.state.lock().await;
        receipt.status = status.into();
        receipt.result = result;
        receipt.error = error;
        receipt.updated_at = Utc::now();
        if self.persist_receipt(receipt).await? {
            self.bus.publish(ServerEvent::Operation {
                session_id: receipt.session_id.clone(),
                operation: OperationDto::from(&*receipt),
            });
        }
        Ok(())
    }
    async fn execute(
        self: Arc<Self>,
        mut receipt: OperationRecord,
        command: Command,
        stop: bool,
        mut admission: Option<OwnedMutexGuard<()>>,
    ) {
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
        let result = self.run(&mut receipt, command, &mut admission).await;
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
        admission: &mut Option<OwnedMutexGuard<()>>,
    ) -> std::result::Result<CommandResult, CommandFailure> {
        match command {
            Command::StartRuntime => {
                let (generation, _) = self.ensure_runtime().await.map_err(start_failure)?;
                self.bind_generation(receipt, &generation).await?;
                Ok(CommandResult::Succeeded(json!({"generation":generation})))
            }
            Command::Prompt {
                message,
                file_ids,
                generation,
            } => {
                // None means the client observed no process. Ordinary dispatch serializes starts.
                if generation.is_none() && self.state.lock().await.runtime.is_some() {
                    return Err(CommandFailure::failed(
                        "generation_mismatch",
                        "Runtime generation changed",
                    ));
                }
                let store = self.store.clone();
                let session_id = receipt.session_id.clone();
                let files = tokio::task::spawn_blocking(move || {
                    uploads::prompt_files(&store, &session_id, &file_ids)
                })
                .await
                .map_err(|err| CommandFailure::failed("invalid_file", err.to_string()))?
                .map_err(|err| CommandFailure::failed("invalid_file", err.to_string()))?;
                let rpc_id = rpc_id(receipt);
                let prepared = prompt::PreparedPrompt::new(&rpc_id, &message, files)?;
                // Invalid attachments and oversized frames must not allocate a process slot.
                let generation = match generation {
                    Some(generation) => generation,
                    None => self.ensure_runtime().await.map_err(start_failure)?.0,
                };
                // Reject known-invalid targets before recording a possible external write.
                Self::prompt_target(&*self.state.lock().await, &generation)?;
                self.bind_generation(receipt, &generation).await?;
                // Commit the safety marker before any prompt bytes can reach OMP.
                // A failed/aborted/no-op RPC may still leave this mapping protected.
                let reference = self
                    .state
                    .lock()
                    .await
                    .session
                    .engine_session_ref
                    .clone()
                    .ok_or_else(|| {
                        CommandFailure::failed(
                            "persistence_unavailable",
                            "OMP session reference is unavailable",
                        )
                    })?;
                let mark_session = receipt.session_id.clone();
                let marked = self
                    .store
                    .run(move |store| store.mark_prompt_may_write(&mark_session, &reference))
                    .await
                    .map_err(|_| {
                        CommandFailure::failed(
                            "persistence_unavailable",
                            "Could not protect OMP history mapping",
                        )
                    })?;
                if !marked {
                    return Err(CommandFailure::failed(
                        "persistence_unavailable",
                        "OMP history mapping changed before prompt",
                    ));
                }
                let (runtime, frame, settled_revision) = {
                    let mut state = self.state.lock().await;
                    // All slow preparation/persistence is done. Validate and choose the route
                    // together, without an await between admission and handing the frame to OMP.
                    let (runtime, steer) = Self::prompt_target(&state, &generation)?;
                    let frame = prepared.frame(steer);
                    state.pending_prompt_results.insert(
                        rpc_id.clone(),
                        (receipt.client_id.clone(), receipt.command_id.clone()),
                    );
                    (runtime, frame, state.settled_revision)
                };
                let response = runtime
                    .request_with_id_after_write(rpc_id.clone(), frame, || drop(admission.take()))
                    .await;
                match response {
                    Ok(response) => {
                        if let Err(err) = self.update_prompt_title(&generation, &message).await {
                            eprintln!("Could not persist latest prompt title: {err}");
                        }
                        if response["data"]["agentInvoked"] == false {
                            self.state
                                .lock()
                                .await
                                .pending_prompt_results
                                .remove(&rpc_id);
                            return Ok(CommandResult::Succeeded(
                                json!({"generation":generation,"agentInvoked":false}),
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
            Command::StopRuntime { generation } => {
                let runtime = self.begin_stop(&generation).await?;
                receipt.runtime_generation = Some(generation.clone());
                {
                    let mut state = self.state.lock().await;
                    self.publish_state(&mut state);
                }
                runtime
                    .stop_confirmed()
                    .await
                    .map_err(|err| CommandFailure::uncertain(err.to_string()))?;
                self.apply_exit(&generation, None).await;
                Ok(CommandResult::Succeeded(
                    json!({"stoppedGeneration":generation}),
                ))
            }
            Command::Interrupt { generation } => {
                let (_, runtime) = self.dispatchable_runtime(&generation).await?;
                self.bind_generation(receipt, &generation).await?;
                runtime
                    .request_after_write(json!({"type":"abort"}), || drop(admission.take()))
                    .await
                    .map_err(rpc_failure)?;
                Ok(CommandResult::Succeeded(json!({"generation":generation})))
            }
            Command::Respond {
                generation,
                input_request_id,
                value,
                confirmed,
                cancelled,
            } => {
                let (_, runtime) = self.dispatchable_runtime(&generation).await?;
                self.bind_generation(receipt, &generation).await?;
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
                    state.update_work_timing(false);
                    attention
                };
                {
                    let mut state = self.state.lock().await;
                    self.publish_state(&mut state);
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
                generation,
                provider,
                model_id,
            } => {
                self.apply_model_setting(
                    receipt,
                    &generation,
                    settings::ModelSetting::Model { provider, model_id },
                )
                .await
            }
            Command::SetFastMode {
                generation,
                enabled,
            } => {
                self.apply_model_setting(
                    receipt,
                    &generation,
                    settings::ModelSetting::FastMode(enabled),
                )
                .await
            }
            Command::SetThinkingLevel { generation, level } => {
                self.apply_model_setting(
                    receipt,
                    &generation,
                    settings::ModelSetting::ThinkingLevel(level),
                )
                .await
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
                "generation_mismatch",
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
        state.update_work_timing(false);
        self.publish_state(&mut state);
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
            state.update_work_timing(true);
            self.publish_state(&mut state);
        }
    }
}
mod projection;
mod prompt;

fn rpc_id(receipt: &OperationRecord) -> String {
    let raw = format!(
        "{}:{}:{}",
        receipt.client_id, receipt.session_id, receipt.command_id
    );
    format!("pc_{}", hex::encode(Sha256::digest(raw.as_bytes())))
}
fn model_info(data: &Value) -> Option<ModelInfo> {
    let model = &data["model"];
    let thinking_levels = (model.get("thinking").is_some() || model.get("reasoning").is_some())
        .then(|| {
            let mut levels = vec!["off".to_owned()];
            if let Some(efforts) = model["thinking"]["efforts"].as_array() {
                for effort in efforts.iter().filter_map(Value::as_str) {
                    if !levels.iter().any(|level| level == effort) {
                        levels.push(effort.to_owned());
                    }
                }
            }
            levels
        });
    Some(ModelInfo {
        provider: omp::string(model, "provider").into(),
        id: omp::string(model, "id").into(),
        name: omp::string(model, "name").into(),
        thinking_level: data["thinkingLevel"].as_str().map(str::to_owned),
        thinking_levels,
        fast_mode_enabled: data["fastModeEnabled"].as_bool(),
        fast_mode_active: data["fastModeActive"].as_bool(),
    })
    .filter(|model| !model.provider.is_empty() && !model.id.is_empty())
}
fn start_failure(err: anyhow::Error) -> CommandFailure {
    let message = format!("{err:#}");
    let code = if message.contains("limit") {
        "runtime_limit"
    } else if message.contains("external_session_busy") {
        "external_session_busy"
    } else if message.contains("history_unavailable") || message.contains("stored OMP session") {
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
