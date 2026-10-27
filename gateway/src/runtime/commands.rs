use super::*;
use crate::history::{FeedbackRecord, HistorySource};
use anyhow::Context;
use std::path::Path;

#[derive(Debug, Serialize, Deserialize)]
pub struct SlashCommand {
    pub name: String,
    pub source: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub aliases: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub input: Option<CommandInput>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub subcommands: Option<Vec<Subcommand>>,
}
#[derive(Debug, Serialize, Deserialize)]
pub struct CommandInput {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hint: Option<String>,
}
#[derive(Debug, Serialize, Deserialize)]
pub struct Subcommand {
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub usage: Option<String>,
}

pub(super) fn managed_catalog(mut commands: Vec<SlashCommand>) -> Vec<SlashCommand> {
    commands.retain(|command| {
        command.source != "builtin"
            || !matches!(command.name.as_str(), "move" | "wt" | "worktree" | "fork")
    });
    for command in &mut commands {
        if command.source == "builtin" && command.name == "session" {
            if let Some(subcommands) = command.subcommands.as_mut() {
                subcommands.retain(|subcommand| subcommand.name != "delete");
            }
            if command
                .input
                .as_ref()
                .and_then(|input| input.hint.as_deref())
                .is_some_and(|hint| hint.contains("delete"))
            {
                command.input = None;
            }
        }
    }
    commands
}

// OMP's text-mode session/delete and move/wt handlers invalidate the Gateway-owned
// transcript/CWD association. Keep these behind Gateway session lifecycle APIs.
// Match OMP's parser, including its colon argument separator; never rewrite input.
pub(super) fn validate_managed_prompt(message: &str) -> std::result::Result<(), CommandFailure> {
    let Some(body) = message.strip_prefix('/') else {
        return Ok(());
    };
    let (name, args) = body
        .split_once(|c: char| c.is_whitespace() || c == ':')
        .unwrap_or((body, ""));
    let verb = args.split_whitespace().next().unwrap_or_default();
    if matches!(name, "move" | "wt" | "worktree" | "fork")
        || (name == "session" && verb.eq_ignore_ascii_case("delete"))
    {
        return Err(CommandFailure::failed(
            "unsupported_capability",
            "This slash command changes managed session history; use Gateway session lifecycle controls instead",
        ));
    }
    Ok(())
}

impl SessionController {
    // Called by the ordered event consumer under feedback_order; no RPC is allowed
    // here because stdout must keep draining even when the OMP reader is backpressured.
    pub(super) async fn command_output(self: &Arc<Self>, generation: &str, text: &str) {
        if text.is_empty() {
            return;
        }
        let (session_id, reference) = {
            let state = self.state.lock().await;
            if state
                .runtime
                .as_ref()
                .is_none_or(|r| r.generation != generation)
                || state.local_timeline_failure.is_some()
            {
                return;
            }
            (
                state.session.id.clone(),
                state.session.engine_session_ref.clone(),
            )
        };
        let id = crate::storage::id("command_");
        let item = TimelineItem {
            id: id.clone(),
            source_id: Some(id),
            message_key: None,
            kind: "system".into(),
            text: text.into(),
            detail: "command output".into(),
            tool: None,
            timestamp: Utc::now(),
        };
        let persist = async {
            let reference = reference.context("OMP session reference unavailable")?;
            let lookup_session = session_id.clone();
            let lookup_reference = reference.clone();
            let file_exists = tokio::fs::try_exists(&reference).await?;
            let allow_missing = self
                .store
                .run(move |store| {
                    store.history_read_policy(&lookup_session, &lookup_reference, file_exists)
                })
                .await?;
            let (anchor, _) = crate::history::response_anchor(HistorySource {
                path: Path::new(&reference),
                allow_missing,
            })
            .await?;
            let record = FeedbackRecord {
                reference,
                anchor,
                sequence: 0,
                item: item.clone(),
            };
            self.store
                .run(move |store| store.record_local_timeline(&session_id, record))
                .await
        }
        .await;
        if let Err(error) = persist {
            self.fail_local_timeline(
                generation,
                format!("Command output could not be saved: {error:#}"),
            )
            .await;
            return;
        }
        self.publish_local_timeline(generation, item).await;
    }

    // Both callers hold feedback_order and pass only an already-durable record.
    pub(super) async fn publish_local_timeline(&self, generation: &str, item: TimelineItem) {
        let mut state = self.state.lock().await;
        if state
            .runtime
            .as_ref()
            .is_none_or(|r| r.generation != generation)
        {
            return;
        }
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

    async fn fail_local_timeline(self: &Arc<Self>, generation: &str, message: String) {
        let (session_id, runtime) = {
            let mut state = self.state.lock().await;
            if state
                .runtime
                .as_ref()
                .is_some_and(|runtime| runtime.generation != generation)
            {
                return;
            }
            let runtime = state
                .runtime
                .as_ref()
                .map(|runtime| runtime.process.clone());
            if state.local_timeline_failure.is_some() {
                return;
            }
            state.local_timeline_failure = Some((generation.to_owned(), message.clone()));
            if let Some(snapshot) = state.projection.as_mut() {
                snapshot.phase = "stopping".into();
                snapshot.activity = Some(message.clone());
            }
            state.update_work_timing(false);
            self.publish_state(&mut state);
            (state.session.id.clone(), runtime)
        };
        let failed_generation = generation.to_owned();
        let durable_message = message.clone();
        match self
            .store
            .run(move |store| {
                store.fail_generation_local_timeline(
                    &session_id,
                    &failed_generation,
                    &durable_message,
                )
            })
            .await
        {
            Ok(receipts) => {
                for receipt in receipts {
                    self.bus.publish(ServerEvent::Operation {
                        session_id: receipt.session_id.clone(),
                        operation: OperationDto::from(&receipt),
                    });
                }
            }
            Err(error) => eprintln!("{message}; could not save generation failure: {error:#}"),
        }
        let Some(runtime) = runtime else {
            return;
        };
        // Never await termination in the ordered stdout consumer: Shutdown may be
        // trying to enqueue its exit behind output that this consumer must drain.
        let controller = self.clone();
        let generation = generation.to_owned();
        tokio::spawn(async move {
            match runtime.stop_confirmed().await {
                Ok(()) => controller.apply_exit(&generation, Some(message)).await,
                Err(error) => eprintln!("Could not stop failed output generation: {error:#}"),
            }
        });
    }
}
