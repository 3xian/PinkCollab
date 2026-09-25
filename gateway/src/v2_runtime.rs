use crate::{
    events::Bus,
    model::{Attention, ModelInfo, TimelineItem},
    omp::{self, Output, Runtime},
    storage::{self, Store},
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
                expected_generation,
            } => {
                ensure!(
                    !message.trim().is_empty() && message.len() <= 262144,
                    "message must be 1..262144 bytes"
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
    controllers: Mutex<HashMap<String, Arc<SessionController>>>,
    store: Arc<Store>,
    browser: Arc<Browser>,
    bus: Arc<Bus>,
    quota: Arc<Semaphore>,
    executable: String,
    args: Vec<String>,
}

impl SessionDirectory {
    pub fn new(
        store: Arc<Store>,
        browser: Arc<Browser>,
        bus: Arc<Bus>,
        executable: String,
        args: Vec<String>,
        max: usize,
    ) -> Arc<Self> {
        Arc::new(Self {
            controllers: Mutex::new(HashMap::new()),
            store,
            browser,
            bus,
            quota: Arc::new(Semaphore::new(max)),
            executable,
            args,
        })
    }
    async fn controller(&self, id: &str) -> Result<Option<Arc<SessionController>>> {
        let mut controllers = self.controllers.lock().await;
        if let Some(existing) = controllers.get(id) {
            return Ok(Some(existing.clone()));
        }
        let Some(session) = self.store.v2_session(id)? else {
            return Ok(None);
        };
        let controller = Arc::new(SessionController {
            state: Mutex::new(ControllerState {
                session,
                runtime: None,
                projection: None,
                messages: Vec::new(),
                finalized_messages: HashSet::new(),
                pending_prompt_results: HashMap::new(),
            }),
            ordinary_dispatch: Mutex::new(()),
            store: self.store.clone(),
            browser: self.browser.clone(),
            bus: self.bus.clone(),
            quota: self.quota.clone(),
            executable: self.executable.clone(),
            args: self.args.clone(),
        });
        controllers.insert(id.into(), controller.clone());
        Ok(Some(controller))
    }
    pub async fn view(&self, id: &str) -> Result<Option<SessionView>> {
        let Some(controller) = self.controller(id).await? else {
            return Ok(None);
        };
        Ok(Some(controller.view().await?))
    }
    pub async fn list(&self) -> Result<Vec<Value>> {
        let sessions = self.store.v2_sessions()?;
        self.summaries(sessions).await
    }
    pub async fn list_page(
        &self,
        after: Option<(&str, &str)>,
        limit: usize,
    ) -> Result<(Vec<Value>, bool)> {
        let (sessions, has_more) = self.store.v2_sessions_page(after, limit)?;
        Ok((self.summaries(sessions).await?, has_more))
    }
    async fn summaries(&self, sessions: Vec<SessionRecord>) -> Result<Vec<Value>> {
        let controllers = self.controllers.lock().await;
        let mut result = Vec::with_capacity(sessions.len());
        for session in sessions {
            let runtime = if let Some(controller) = controllers.get(&session.id) {
                controller.state.lock().await.projection.clone()
            } else {
                None
            };
            result.push(json!({"session":session,"runtime":runtime}));
        }
        Ok(result)
    }
    pub async fn submit(
        self: &Arc<Self>,
        client_id: String,
        session_id: String,
        command_id: String,
        command: Command,
    ) -> std::result::Result<Submitted, SubmitError> {
        if command_id.is_empty()
            || command_id.len() > 128
            || !command_id
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
        {
            return Err(SubmitError::Invalid("Valid commandId required".into()));
        }
        command
            .validate()
            .map_err(|err| SubmitError::Invalid(err.to_string()))?;
        let controller = self
            .controller(&session_id)
            .await
            .map_err(|_| SubmitError::Persistence)?
            .ok_or(SubmitError::NotFound)?;
        let fingerprint = hex::encode(Sha256::digest(
            serde_json::to_vec(&command)
                .map_err(|_| SubmitError::Invalid("Invalid command".into()))?,
        ));
        let now = Utc::now();
        let receipt = OperationRecord {
            command_id: command_id.clone(),
            client_id: client_id.clone(),
            session_id: session_id.clone(),
            command_type: command.name().into(),
            request_fingerprint: fingerprint.clone(),
            runtime_generation: None,
            status: "accepted".into(),
            result: None,
            error: None,
            created_at: now,
            updated_at: now,
        };
        let inserted = self.store.insert_operation(&receipt);
        match inserted {
            Ok(false) => {
                let previous = self
                    .store
                    .operation(&client_id, &session_id, &command_id)
                    .map_err(|_| SubmitError::Persistence)?
                    .ok_or(SubmitError::Persistence)?;
                if previous.request_fingerprint != fingerprint {
                    return Err(SubmitError::Conflict);
                }
                Ok(Submitted {
                    receipt: previous,
                    replayed: true,
                    receipt_stored: true,
                })
            }
            Ok(true) => {
                let stop = matches!(command, Command::StopRuntime { .. });
                tokio::spawn(async move {
                    controller.execute(receipt.clone(), command, stop).await;
                });
                // Read from storage because the spawned task may already have advanced it.
                let current = self
                    .store
                    .operation(&client_id, &session_id, &command_id)
                    .map_err(|_| SubmitError::Persistence)?
                    .ok_or(SubmitError::Persistence)?;
                Ok(Submitted {
                    receipt: current,
                    replayed: false,
                    receipt_stored: true,
                })
            }
            Err(_) if matches!(command, Command::StopRuntime { .. }) => {
                let copy = receipt.clone();
                tokio::spawn(async move {
                    controller.execute(copy, command, true).await;
                });
                Ok(Submitted {
                    receipt,
                    replayed: false,
                    receipt_stored: false,
                })
            }
            Err(_) => Err(SubmitError::Persistence),
        }
    }
    pub fn operation(
        &self,
        client_id: &str,
        session_id: &str,
        command_id: &str,
    ) -> Result<Option<OperationRecord>> {
        self.store.operation(client_id, session_id, command_id)
    }
    pub async fn models(&self, id: &str) -> Result<(Vec<ModelInfo>, Vec<String>)> {
        let controller = self.controller(id).await?.context("session not found")?;
        let runtime = {
            controller
                .state
                .lock()
                .await
                .runtime
                .as_ref()
                .map(|r| r.process.clone())
                .context("runtime required")?
        };
        let response = runtime
            .request(json!({"type":"get_available_models"}))
            .await?;
        let models = response["data"]["models"]
            .as_array()
            .context("OMP returned no model list")?
            .iter()
            .map(|value| {
                model_info(&json!({"model":value})).context("OMP returned an invalid model")
            })
            .collect::<Result<Vec<_>>>()?;
        let response = runtime
            .request(json!({"type":"get_available_thinking_levels"}))
            .await?;
        let levels = response["data"]["levels"]
            .as_array()
            .context("OMP returned no thinking levels")?
            .iter()
            .filter_map(|value| value.as_str().map(str::to_owned))
            .collect();
        Ok((models, levels))
    }
    pub async fn close(&self) {
        let controllers = self
            .controllers
            .lock()
            .await
            .values()
            .cloned()
            .collect::<Vec<_>>();
        for controller in controllers {
            controller.stop_on_shutdown().await;
        }
    }
}

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
        let recent_operations = self.store.recent_operations(&session.id, 20)?;
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
        if kind.starts_with("v2.runtime")
            || kind == "v2.session.changed"
            || kind == "v2.metadata.updated"
        {
            self.bus.publish_resource("host/sessions",json!([{"type":"summary.changed","changeKind":kind,"sessionId":id,"value":payload}]));
        }
    }
    async fn persist_receipt(&self, receipt: &OperationRecord) -> Result<bool> {
        let store = self.store.clone();
        let receipt = receipt.clone();
        tokio::task::spawn_blocking(move || store.update_operation(&receipt))
            .await
            .context("receipt writer stopped")?
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
        let _guard = if stop {
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
                expected_generation,
            } => {
                let (generation, runtime) = match delivery {
                    Delivery::Start => self.ensure_runtime().await.map_err(start_failure)?,
                    _ => {
                        self.current_runtime(expected_generation.as_deref().unwrap_or_default())
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
                let rpc_id = rpc_id(receipt);
                {
                    let mut state = self.state.lock().await;
                    state.pending_prompt_results.insert(
                        rpc_id.clone(),
                        (receipt.client_id.clone(), receipt.command_id.clone()),
                    );
                }
                let mut frame = json!({"type":"prompt","message":message});
                match delivery {
                    Delivery::Start => {}
                    Delivery::Steer => frame["streamingBehavior"] = json!("steer"),
                    Delivery::FollowUp => frame["streamingBehavior"] = json!("followUp"),
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
                        self.set_execution(&generation, "active", None).await;
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
                let (_, runtime) = self.current_runtime(&expected_generation).await?;
                receipt.runtime_generation = Some(expected_generation.clone());
                {
                    let mut state = self.state.lock().await;
                    if let Some(snapshot) = state.projection.as_mut() {
                        snapshot.phase = "stopping".into();
                    }
                }
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
                let (_, runtime) = self.current_runtime(&expected_generation).await?;
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
                let (_, runtime) = self.current_runtime(&expected_generation).await?;
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
                let (_, runtime) = self.current_runtime(&expected_generation).await?;
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
                let (_, runtime) = self.current_runtime(&expected_generation).await?;
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
        if !self
            .store
            .bind_operation_generation(receipt, generation)
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
    async fn current_runtime(
        &self,
        expected: &str,
    ) -> std::result::Result<(String, Arc<Runtime>), CommandFailure> {
        let state = self.state.lock().await;
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
        ensure!(
            self.store.reserve_runtime(&session.id, &generation)?,
            "previous runtime exit is not confirmed"
        );
        let (runtime, mut output) = match Runtime::spawn(&self.executable, &self.args, &cwd) {
            Ok(started) => started,
            Err(err) => {
                let _ = self.store.release_runtime(&session.id, &generation);
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
            state.messages.clear();
            state.finalized_messages.clear();
        }
        {
            let state = self.state.lock().await;
            self.publish(
                &session.id,
                "v2.runtime.updated",
                json!({"runtime":state.projection}),
            );
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
                ensure!(
                    self.store
                        .set_engine_ref(&session.id, session.metadata_revision, reference)?,
                    "session mapping changed during startup"
                );
            } else {
                ensure!(
                    session.engine_session_ref.as_deref() == Some(reference),
                    "OMP loaded a different session"
                );
            }
            let saved = self
                .store
                .v2_session(&session.id)?
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
    async fn set_execution(&self, generation: &str, execution: &str, activity: Option<String>) {
        let mut state = self.state.lock().await;
        if state
            .runtime
            .as_ref()
            .is_some_and(|r| r.generation == generation)
        {
            if let Some(snapshot) = state.projection.as_mut() {
                snapshot.execution = execution.into();
                snapshot.activity = activity;
            }
            let id = state.session.id.clone();
            self.publish(
                &id,
                "v2.runtime.updated",
                json!({"runtime":state.projection}),
            );
        }
    }
    async fn apply_frame(self: &Arc<Self>, generation: &str, frame: Value) {
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
                    let Ok(Some(mut receipt)) =
                        controller
                            .store
                            .operation(&client_id, &session_id, &command_id)
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
        match kind.as_str() {
            "agent_start" => {
                if let Some(snapshot) = state.projection.as_mut() {
                    snapshot.execution = "active".into();
                }
            }
            "session_settled" => {
                if let Some(snapshot) = state.projection.as_mut() {
                    snapshot.execution = "quiescent".into();
                }
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
                        }
                    } else if method == "cancel" {
                        snapshot
                            .pending_inputs
                            .retain(|a| a.id != omp::string(&frame, "targetId"));
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
                }
            }
            "message_update" if frame["assistantMessageEvent"]["type"] == "text_delta" => {
                let engine_id = omp::string(&frame, "messageId");
                if !engine_id.is_empty() {
                    let message_id = format!("{generation}:{engine_id}");
                    if !state.finalized_messages.contains(&message_id) {
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
            }
        }
        self.publish(
            &id,
            "v2.session.changed",
            json!({"runtime":state.projection,"messages":state.messages}),
        );
    }
    async fn apply_exit(&self, generation: &str, reason: Option<String>) {
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
            state.session.id.clone()
        };
        self.publish(
            &session_id,
            "v2.runtime.exited",
            json!({"generation":generation,"reason":reason}),
        );
        let store = self.store.clone();
        let cleanup_session = session_id.clone();
        let cleanup_generation = generation.to_owned();
        let cleanup = tokio::task::spawn_blocking(move || {
            let unknown = store.mark_generation_unknown(&cleanup_session, &cleanup_generation);
            let release = store.release_runtime(&cleanup_session, &cleanup_generation);
            (unknown, release)
        })
        .await;
        if let Ok((Err(err), _)) = &cleanup {
            eprintln!("Could not mark exited runtime operations uncertain: {err}");
        }
        if let Ok((_, Err(err))) = cleanup {
            eprintln!("Could not release confirmed runtime lease: {err}");
        }
    }
    async fn stop_on_shutdown(&self) {
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
