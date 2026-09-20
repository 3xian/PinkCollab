use crate::{
    events::{self, Bus},
    model::{Detail, ModelInfo, Session, TimelineItem},
    omp::{self, Output, Runtime},
    storage::{self, Store},
    workspace::{self, Browser},
};
use anyhow::{Context, Result, bail, ensure};
use chrono::Utc;
use serde::Deserialize;
use serde_json::{Value, json};
use std::{collections::HashMap, path::Path, sync::Arc};
use tokio::sync::Mutex;

/// How many timeline items a session keeps in memory. Both the live timeline and a reconstructed
/// history use the same number and the same retention policy.
const TIMELINE_LIMIT: usize = 500;

struct Entry {
    session: Session,
    timeline: Vec<TimelineItem>,
    model: Option<ModelInfo>,
    runtime: Option<Arc<Runtime>>,
    command: Arc<Mutex<()>>,
    stopping: bool,
    interrupted: bool,
}
struct State {
    entries: HashMap<String, Entry>,
    closing: bool,
}
pub struct Registry {
    state: Mutex<State>,
    store: Arc<Store>,
    pub bus: Arc<Bus>,
    browser: Arc<Browser>,
    host_id: String,
    executable: String,
    args: Vec<String>,
    max: usize,
}
#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InputResponse {
    pub id: String,
    #[serde(default)]
    pub value: String,
    pub confirmed: Option<bool>,
    #[serde(default)]
    pub cancelled: bool,
}

impl Registry {
    pub fn new(
        store: Arc<Store>,
        bus: Arc<Bus>,
        browser: Arc<Browser>,
        host_id: String,
        executable: String,
        args: Vec<String>,
        max: usize,
    ) -> Result<Arc<Self>> {
        let mut entries = HashMap::new();
        for mut session in store.sessions()? {
            session.runtime_attached = false;
            if ["starting", "running", "needs_input", "idle"].contains(&session.status.as_str()) {
                session.status = "offline".into();
                session.activity = "Gateway restarted; runtime is no longer attached".into();
                session.needs_attention = false;
                session.attention = None;
                store.save(&session)?;
            }
            entries.insert(
                session.id.clone(),
                Entry {
                    session,
                    timeline: Vec::new(),
                    model: None,
                    runtime: None,
                    command: Arc::new(Mutex::new(())),
                    stopping: false,
                    interrupted: false,
                },
            );
        }
        Ok(Arc::new(Self {
            state: Mutex::new(State {
                entries,
                closing: false,
            }),
            store,
            bus,
            browser,
            host_id,
            executable,
            args,
            max,
        }))
    }
    pub async fn list(&self) -> Vec<Session> {
        let state = self.state.lock().await;
        let mut list: Vec<_> = state.entries.values().map(|e| e.session.clone()).collect();
        list.sort_by_key(|session| std::cmp::Reverse(session.updated_at));
        list
    }
    pub async fn detail(&self, id: &str) -> Result<Detail> {
        let (session, timeline, model) = {
            let state = self.state.lock().await;
            let e = state.entries.get(id).context("session not found")?;
            (
                e.session.clone(),
                e.timeline.clone(),
                e.model.clone(),
            )
        };
        // OMP owns transcripts. Read its branch-aware session log on demand instead of copying it to SQLite.
        let timeline = if timeline.is_empty() && !session.session_file.is_empty() {
            history(Path::new(&session.session_file))
                .await
                .unwrap_or_default()
        } else {
            timeline
        };
        Ok(Detail {
            session,
            timeline,
            model,
        })
    }
    pub async fn cycle_model(&self, id: &str) -> Result<ModelInfo> {
        let (gate, runtime) = self.runtime(id).await?;
        let _guard = gate.lock().await;
        let response = runtime.request(json!({"type":"cycle_model"})).await?;
        let model = response["data"]
            .get("model")
            .filter(|model| !model.is_null())
            .context("no alternative model is configured")?;
        let model = model_info(model)?;
        self.set_model(id, Some(model.clone())).await;
        Ok(model)
    }
    /// OMP can switch models without Gateway involvement, so re-read its state to keep the cache canonical.
    async fn refresh_model(&self, id: &str) -> Result<()> {
        let (gate, runtime) = self.runtime(id).await?;
        let _guard = gate.lock().await;
        let response = runtime.request(json!({"type":"get_state"})).await?;
        self.set_model(id, state_model(&response)).await;
        Ok(())
    }
    async fn set_model(&self, id: &str, model: Option<ModelInfo>) {
        let mut state = self.state.lock().await;
        let Some(e) = state.entries.get_mut(id) else {
            return;
        };
        if e.model == model {
            return;
        }
        e.model = model.clone();
        self.bus
            .publish("model.updated", json!({"sessionId":id,"model":model}));
    }
    async fn runtime(&self, id: &str) -> Result<(Arc<Mutex<()>>, Arc<Runtime>)> {
        let state = self.state.lock().await;
        let e = state.entries.get(id).context("session not found")?;
        let runtime = e
            .runtime
            .clone()
            .filter(|runtime| runtime.alive())
            .context("session has no attached OMP runtime")?;
        Ok((e.command.clone(), runtime))
    }
    fn save(&self, e: &mut Entry) -> Result<()> {
        e.session.updated_at = Utc::now();
        self.store.save(&e.session)?;
        self.bus.publish("session.updated", json!(e.session));
        Ok(())
    }
    fn item(&self, e: &mut Entry, mut item: TimelineItem) {
        if item.id.is_empty() {
            item.id = storage::id("item_");
        }
        let published = match e.timeline.iter_mut().find(|value| value.id == item.id) {
            // A tool result arrives as a second frame for the same call: it only completes the
            // item, which keeps the call's original position and start timestamp.
            Some(old) if old.tool.is_some() && item.tool.is_some() => {
                old.merge_tool_update(item);
                old.clone()
            }
            Some(old) => {
                if item.detail.is_empty() {
                    item.detail = std::mem::take(&mut old.detail);
                }
                *old = item.clone();
                item
            }
            None => {
                e.timeline.push(item.clone());
                trim_timeline(&mut e.timeline, TIMELINE_LIMIT);
                item
            }
        };
        self.bus.publish(
            "timeline.updated",
            events::timeline_payload(&e.session.id, &published),
        );
    }
    async fn handle(&self, id: &str, frame: Value) -> Result<()> {
        let model_changed = omp::string(&frame, "type") == "model_changed";
        let result = self.apply(id, frame).await;
        if model_changed && let Err(err) = self.refresh_model(id).await {
            eprintln!("Model refresh error: {err}");
        }
        result
    }
    async fn apply(&self, id: &str, f: Value) -> Result<()> {
        let mut state = self.state.lock().await;
        let Some(e) = state.entries.get_mut(id) else {
            return Ok(());
        };
        if e.stopping {
            return Ok(());
        }
        if omp::string(&f, "type") == "message_update"
            && f["assistantMessageEvent"]["type"] == "text_delta"
        {
            self.bus.publish(
                "message.delta",
                json!({"sessionId":id,"text":omp::string(&f["assistantMessageEvent"],"delta")}),
            );
        }
        if f["type"] == "extension_ui_request"
            && f["method"] == "cancel"
            && e.session
                .attention
                .as_ref()
                .is_some_and(|a| a.id != omp::string(&f, "targetId"))
        {
            return Ok(());
        }
        let mut update = events::normalize(&f);
        let changed = update.status.is_some()
            || update.activity.is_some()
            || update.attention.is_some()
            || update.clear_attention;
        if let Some(status) = update.status {
            let status = if status == "completed" && e.session.status == "failed" {
                update.activity = Some(e.session.activity.clone());
                "failed"
            } else if status == "completed" && e.interrupted {
                e.interrupted = false;
                update.activity = Some("Interrupted".into());
                "idle"
            } else {
                status
            };
            e.session.status = status.into();
        }
        if let Some(activity) = update.activity
            && !activity.is_empty()
        {
            e.session.activity = activity;
        }
        if update.clear_attention {
            e.session.attention = None;
            e.session.needs_attention = false;
        }
        if let Some(attention) = update.attention {
            e.session.needs_attention = true;
            self.bus.publish(
                "attention.created",
                json!({"sessionId":id,"attention":attention}),
            );
            e.session.attention = Some(attention);
        }
        if let Some(item) = update.item {
            self.item(e, item);
        }
        if changed {
            self.save(e)?;
        }
        Ok(())
    }
    async fn exit(&self, id: &str, error: Option<String>) -> Result<()> {
        let mut state = self.state.lock().await;
        let Some(e) = state.entries.get_mut(id) else {
            return Ok(());
        };
        e.runtime = None;
        e.model = None;
        e.session.runtime_attached = false;
        e.session.attention = None;
        e.session.needs_attention = false;
        if e.stopping {
            e.session.status = "stopped".into();
            e.session.activity = "Stopped".into();
        } else if let Some(reason) = error {
            // The transport reports why it gave up; surfacing it beats a fixed string that hides it.
            e.session.status = "failed".into();
            e.session.activity = reason;
        } else if !["completed", "failed"].contains(&e.session.status.as_str()) {
            e.session.status = "stopped".into();
            e.session.activity = "OMP process exited".into();
        }
        self.save(e)
    }
    pub async fn create(
        self: &Arc<Self>,
        host_id: String,
        cwd: String,
        prompt: String,
        title: String,
    ) -> Result<Session> {
        // Client disconnects must not abandon a starting process or a half-registered session.
        let registry = self.clone();
        tokio::spawn(async move { registry.create_inner(host_id, cwd, prompt, title).await })
            .await?
    }
    async fn create_inner(
        self: &Arc<Self>,
        host_id: String,
        cwd: String,
        prompt: String,
        title: String,
    ) -> Result<Session> {
        ensure!(
            host_id == self.host_id,
            "hostId does not match this gateway"
        );
        let cwd = self.browser.validate(Path::new(&cwd))?;
        ensure!(
            !prompt.trim().is_empty() && prompt.len() <= 262144,
            "prompt must be 1..262144 bytes"
        );
        let title = if title.is_empty() {
            prompt.chars().take(80).collect()
        } else {
            title.chars().take(80).collect()
        };
        let now = Utc::now();
        let session = Session {
            id: storage::id("sess_"),
            host_id,
            cwd: workspace::display(&cwd),
            title,
            status: "starting".into(),
            activity: "Starting OMP".into(),
            needs_attention: false,
            runtime_attached: false,
            attention: None,
            created_at: now,
            updated_at: now,
            session_file: String::new(),
        };
        let command = Arc::new(Mutex::new(()));
        let _guard = command.lock().await;
        let session_id = session.id.clone();
        {
            let mut state = self.state.lock().await;
            ensure!(!state.closing, "gateway is shutting down");
            ensure!(
                state
                    .entries
                    .values()
                    .filter(|e| e.runtime.is_some() || e.session.status == "starting")
                    .count()
                    < self.max,
                "OMP runtime limit reached"
            );
            self.store.save(&session)?;
            self.bus.publish("session.updated", json!(session));
            state.entries.insert(
                session_id.clone(),
                Entry {
                    session,
                    timeline: Vec::new(),
                    model: None,
                    runtime: None,
                    command: command.clone(),
                    stopping: false,
                    interrupted: false,
                },
            );
        }
        let started = Runtime::spawn(&self.executable, &self.args, &cwd);
        let (runtime, mut output) = match started {
            Ok(v) => v,
            Err(err) => {
                self.exit(&session_id, Some(format!("{err:#}"))).await?;
                return Err(err);
            }
        };
        {
            let mut state = self.state.lock().await;
            let entry = state.entries.get_mut(&session_id).unwrap();
            entry.runtime = Some(runtime.clone());
            entry.session.runtime_attached = true;
            self.save(entry)?;
        }
        let weak = Arc::downgrade(self);
        let event_id = session_id.clone();
        tokio::spawn(async move {
            while let Some(event) = output.recv().await {
                let Some(registry) = weak.upgrade() else {
                    break;
                };
                let result = match event {
                    Output::Frame(f) => registry.handle(&event_id, f).await,
                    Output::Exited(err) => registry.exit(&event_id, err).await,
                };
                if let Err(err) = result {
                    eprintln!("Session metadata error: {err}");
                }
            }
        });
        let result = async {
            runtime.wait_ready().await?;
            let response = runtime.request(json!({"type":"get_state"})).await?;
            {
                let mut state = self.state.lock().await;
                let e = state.entries.get_mut(&session_id).unwrap();
                e.session.session_file = omp::string(&response["data"], "sessionFile").into();
                e.model = state_model(&response);
                self.item(e, events::item("user", &prompt, ""));
                self.save(e)?;
            }
            let ack = runtime
                .request(json!({"type":"prompt","message":prompt}))
                .await?;
            if ack["data"]["agentInvoked"] == false {
                self.apply(
                    &session_id,
                    json!({"type":"prompt_result","success":true,"data":{"agentInvoked":false}}),
                )
                .await?;
            }
            Ok::<(), anyhow::Error>(())
        }
        .await;
        if let Err(err) = result {
            {
                let mut state = self.state.lock().await;
                let e = state.entries.get_mut(&session_id).unwrap();
                e.session.status = "failed".into();
                e.session.activity = "OMP startup or initial prompt failed".into();
                self.save(e)?;
            }
            runtime.stop().await;
            return Err(err);
        }
        Ok(self.detail(&session_id).await?.session)
    }
    pub async fn command(
        self: &Arc<Self>,
        id: String,
        command: String,
        message: String,
        response: InputResponse,
    ) -> Result<()> {
        let registry = self.clone();
        tokio::spawn(async move {
            registry
                .command_inner(&id, &command, message, response)
                .await
        })
        .await?
    }
    async fn command_inner(
        &self,
        id: &str,
        command: &str,
        message: String,
        response: InputResponse,
    ) -> Result<()> {
        let gate = {
            let state = self.state.lock().await;
            state
                .entries
                .get(id)
                .context("session not found")?
                .command
                .clone()
        };
        let _guard = gate.lock().await;
        let (runtime, session) = {
            let state = self.state.lock().await;
            let e = state.entries.get(id).context("session not found")?;
            (
                e.runtime
                    .clone()
                    .filter(|r| r.alive())
                    .context("session has no attached OMP runtime")?,
                e.session.clone(),
            )
        };
        match command {
            "prompt" => {
                ensure!(
                    !message.trim().is_empty() && message.len() <= 262144,
                    "prompt must be 1..262144 bytes"
                );
                ensure!(
                    session.attention.is_none(),
                    "answer the pending input first"
                );
                let mut frame = json!({"type":"prompt","message":message});
                if session.status == "running" {
                    frame["streamingBehavior"] = json!("steer");
                }
                // Clear the interrupt marker before sending; agent events may precede the response.
                {
                    let mut state = self.state.lock().await;
                    state.entries.get_mut(id).unwrap().interrupted = false;
                }
                runtime.request(frame).await?;
                let mut state = self.state.lock().await;
                self.item(
                    state.entries.get_mut(id).unwrap(),
                    events::item("user", message, ""),
                );
            }
            "interrupt" => {
                {
                    let mut state = self.state.lock().await;
                    state.entries.get_mut(id).unwrap().interrupted = true;
                }
                runtime.request(json!({"type":"abort"})).await?;
                let mut state = self.state.lock().await;
                let e = state.entries.get_mut(id).unwrap();
                e.session.status = "idle".into();
                e.session.activity = "Interrupted".into();
                e.session.attention = None;
                e.session.needs_attention = false;
                self.save(e)?;
            }
            "stop" => {
                {
                    let mut state = self.state.lock().await;
                    state.entries.get_mut(id).unwrap().stopping = true;
                }
                runtime.stop().await;
                self.exit(id, None).await?;
            }
            "respond" => {
                let a = session.attention.context("no input pending")?;
                ensure!(a.id == response.id, "attention is no longer pending");
                ensure!(response.value.len() <= 262144, "answer too large");
                let mut frame = json!({"type":"extension_ui_response","id":a.id});
                let text = if response.cancelled {
                    frame["cancelled"] = json!(true);
                    "Input cancelled".into()
                } else if a.kind == "confirm" {
                    let confirmed = response.confirmed.context("confirmed boolean required")?;
                    frame["confirmed"] = json!(confirmed);
                    format!("Confirmed: {confirmed}")
                } else {
                    ensure!(
                        a.kind != "select" || a.options.contains(&response.value),
                        "answer must match a select option"
                    );
                    frame["value"] = json!(response.value);
                    format!("Answered: {}", response.value)
                };
                runtime.write(frame).await?;
                let mut state = self.state.lock().await;
                let e = state.entries.get_mut(id).unwrap();
                if e.session
                    .attention
                    .as_ref()
                    .is_some_and(|current| current.id == a.id)
                {
                    e.session.attention = None;
                    e.session.needs_attention = false;
                    e.session.status = "running".into();
                    e.session.activity = "Thinking".into();
                    self.save(e)?;
                }
                self.item(e, events::item("user", text, ""));
            }
            _ => bail!("unknown command"),
        }
        Ok(())
    }
    pub async fn delete(&self, id: &str) -> Result<()> {
        let mut state = self.state.lock().await;
        let e = state.entries.get(id).context("session not found")?;
        ensure!(
            e.runtime.is_none() && e.session.status != "starting",
            "stop session before deleting metadata"
        );
        self.store.delete(id)?;
        state.entries.remove(id);
        self.bus.publish("session.deleted", json!({"sessionId":id}));
        Ok(())
    }
    pub async fn close(&self) {
        let gates = {
            let mut state = self.state.lock().await;
            state.closing = true;
            state
                .entries
                .iter()
                .map(|(id, e)| (id.clone(), e.command.clone()))
                .collect::<Vec<_>>()
        };
        for (id, gate) in gates {
            let _guard = gate.lock().await;
            let runtime = {
                let mut state = self.state.lock().await;
                let e = state.entries.get_mut(&id).unwrap();
                e.stopping = true;
                e.runtime.clone()
            };
            if let Some(runtime) = runtime {
                runtime.stop().await;
                let _ = self.exit(&id, None).await;
            }
        }
    }
}

fn state_model(response: &Value) -> Option<ModelInfo> {
    response["data"]
        .get("model")
        .filter(|model| !model.is_null())
        .and_then(|model| model_info(model).ok())
}

fn model_info(value: &Value) -> Result<ModelInfo> {
    let provider = omp::string(value, "provider");
    let id = omp::string(value, "id");
    let name = omp::string(value, "name");
    ensure!(
        !provider.is_empty() && !id.is_empty() && !name.is_empty(),
        "OMP returned an invalid model"
    );
    Ok(ModelInfo {
        provider: provider.into(),
        id: id.into(),
        name: name.into(),
    })
}

pub async fn history(path: &Path) -> Result<Vec<TimelineItem>> {
    use futures_util::StreamExt;
    use tokio_util::codec::{FramedRead, LinesCodec};
    let file = tokio::fs::File::open(path).await?;
    let mut lines = FramedRead::new(file, LinesCodec::new_with_max_length(16 * 1024 * 1024));
    let mut entries = HashMap::<String, Value>::new();
    let mut leaf = String::new();
    while let Some(line) = lines.next().await {
        let line = line?;
        let Ok(entry) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        let id = omp::string(&entry, "id");
        if !id.is_empty() {
            leaf = id.into();
            entries.insert(id.into(), entry);
        }
    }
    let mut chain = vec![];
    let mut visited = std::collections::HashSet::new();
    while !leaf.is_empty() && visited.insert(leaf.clone()) {
        let Some(entry) = entries.get(&leaf) else {
            break;
        };
        chain.push(entry);
        leaf = omp::string(entry, "parentId").into();
    }
    chain.reverse();
    let mut timeline: Vec<TimelineItem> = vec![];
    for entry in chain {
        if entry["type"] != "message" {
            continue;
        }
        let m = &entry["message"];
        let role = omp::string(m, "role");
        let timestamp = omp::string(entry, "timestamp")
            .parse()
            .unwrap_or_else(|_| Utc::now());
        if role == "toolResult" || role == "tool" {
            let call_id = omp::string(m, "toolCallId");
            if call_id.is_empty() {
                continue;
            }
            let result = omp::text_content(m);
            upsert_tool(
                &mut timeline,
                TimelineItem::tool_completed(
                    call_id,
                    omp::string(m, "toolName"),
                    result,
                    m["isError"] == true,
                    timestamp,
                ),
            );
            continue;
        }
        if !["user", "assistant"].contains(&role) {
            continue;
        }
        let entry_id = omp::string(entry, "id");
        if role == "assistant"
            && let Some(parts) = m["content"].as_array()
        {
            for (index, part) in parts.iter().enumerate() {
                match omp::string(part, "type") {
                    "text" => {
                        let text = omp::string(part, "text").trim();
                        if !text.is_empty() {
                            timeline.push(TimelineItem {
                                id: format!("{entry_id}:{index}"),
                                kind: "assistant".into(),
                                text: text.into(),
                                detail: String::new(),
                                tool: None,
                                timestamp,
                            });
                        }
                    }
                    "toolCall" => {
                        let call_id = omp::string(part, "id");
                        let name = omp::string(part, "name");
                        if call_id.is_empty() || name.is_empty() {
                            continue;
                        }
                        let arguments = part["arguments"].clone();
                        upsert_tool(
                            &mut timeline,
                            TimelineItem::tool_started(call_id, name, arguments, timestamp),
                        );
                    }
                    _ => {}
                }
            }
            if omp::string(m, "stopReason") == "error" {
                timeline.push(TimelineItem {
                    id: format!("{entry_id}:error"),
                    kind: "error".into(),
                    text: omp::string(m, "errorMessage").into(),
                    detail: String::new(),
                    tool: None,
                    timestamp,
                });
            }
            continue;
        }
        let text = m["content"]
            .as_str()
            .map(str::to_owned)
            .unwrap_or_else(|| omp::text_content(m));
        if !text.is_empty() {
            timeline.push(TimelineItem {
                id: entry_id.into(),
                kind: role.into(),
                text,
                detail: String::new(),
                tool: None,
                timestamp,
            });
        }
    }
    trim_timeline(&mut timeline, TIMELINE_LIMIT);
    Ok(timeline)
}

/// Upserts a tool item by call id. A transcript can hold the result of a call in a different entry
/// than the call itself (or in the other order), and both frames describe one call.
fn upsert_tool(timeline: &mut Vec<TimelineItem>, item: TimelineItem) {
    match timeline.iter_mut().find(|existing| existing.id == item.id) {
        Some(existing) => existing.merge_tool_update(item),
        None => timeline.push(item),
    }
}

fn is_visible_timeline_boundary(item: &TimelineItem) -> bool {
    matches!(item.kind.as_str(), "user" | "assistant" | "error")
}

/// Drops the oldest hidden bookkeeping before the oldest visible message, so a burst of tool calls
/// cannot push the conversation itself out of the timeline. Shared by the live timeline and by
/// reconstructed history, which are documented as using the same retention policy.
fn trim_timeline(timeline: &mut Vec<TimelineItem>, limit: usize) {
    while timeline.len() > limit {
        let index = timeline
            .iter()
            .position(|item| !is_visible_timeline_boundary(item))
            .unwrap_or(0);
        timeline.remove(index);
    }
}
