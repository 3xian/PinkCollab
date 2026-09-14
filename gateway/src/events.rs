use crate::{
    model::{Attention, Event, TimelineItem},
    omp::{string, text_content},
    storage::id,
};
use chrono::Utc;
use serde_json::{Value, json};
use std::sync::Mutex;
use tokio::sync::broadcast;

pub struct Bus {
    state: Mutex<(u64, broadcast::Sender<Event>)>,
}
impl Default for Bus {
    fn default() -> Self {
        let (tx, _) = broadcast::channel(256);
        Self {
            state: Mutex::new((0, tx)),
        }
    }
}
impl Bus {
    pub fn publish(&self, kind: &str, payload: Value) {
        let mut state = self.state.lock().unwrap();
        state.0 += 1;
        let _ = state.1.send(Event {
            sequence: state.0,
            kind: kind.into(),
            timestamp: Utc::now(),
            payload,
        });
    }
    pub fn subscribe(&self) -> broadcast::Receiver<Event> {
        self.state.lock().unwrap().1.subscribe()
    }
}
#[derive(Default)]
pub struct Update {
    pub status: Option<&'static str>,
    pub activity: Option<String>,
    pub item: Option<TimelineItem>,
    pub attention: Option<Attention>,
    pub clear_attention: bool,
}
pub fn item(kind: &str, text: impl Into<String>, detail: impl Into<String>) -> TimelineItem {
    TimelineItem {
        id: id("item_"),
        kind: kind.into(),
        text: text.into(),
        detail: detail.into(),
        timestamp: Utc::now(),
    }
}
pub fn normalize(f: &Value) -> Update {
    let mut u = Update::default();
    match string(f, "type") {
        "agent_start" => {
            u.status = Some("running");
            u.activity = Some("Thinking".into());
        }
        "agent_end" => {
            u.status = Some("completed");
            u.activity = Some("Completed".into());
            u.clear_attention = true;
        }
        "message_end" => {
            let m = &f["message"];
            if m["role"] == "assistant" {
                let text = text_content(m);
                if !text.is_empty() {
                    u.item = Some(item("assistant", text, ""));
                }
                match string(m, "stopReason") {
                    "error" => {
                        u.status = Some("failed");
                        u.activity = Some("Agent failed".into());
                        u.item = Some(item("error", string(m, "errorMessage"), ""));
                    }
                    "aborted" => {
                        u.status = Some("idle");
                        u.activity = Some("Interrupted".into());
                    }
                    _ => {}
                }
            }
        }
        "tool_execution_start" => {
            let name = string(f, "toolName");
            let activity = match name {
                "edit" | "write" | "ast_edit" => "Editing",
                "bash" => {
                    let cmd = string(&f["args"], "command").to_lowercase();
                    if [
                        "test",
                        "pytest",
                        "check",
                        "gradle",
                        "go vet",
                        "cargo clippy",
                    ]
                    .iter()
                    .any(|token| cmd.contains(token))
                    {
                        "Testing"
                    } else {
                        "Running command"
                    }
                }
                _ => "Investigating",
            };
            u.activity = Some(activity.into());
            let mut i = item(
                "tool",
                format!("{activity} · {name}"),
                f["args"].to_string(),
            );
            i.id = string(f, "toolCallId").into();
            u.item = Some(i);
        }
        "tool_execution_end" => {
            let prefix = if f["isError"] == true {
                "Tool failed"
            } else {
                "Finished"
            };
            let mut i = item(
                "tool",
                format!("{prefix} · {}", string(f, "toolName")),
                text_content(&f["result"]),
            );
            i.id = string(f, "toolCallId").into();
            u.item = Some(i);
        }
        "prompt_result" => {
            if f["success"] == false {
                u.status = Some("failed");
                u.activity = Some("Prompt failed".into());
                u.item = Some(item("error", string(f, "error"), ""));
            } else if f["data"]["agentInvoked"] == false {
                u.status = Some("idle");
                u.activity = Some("Ready".into());
            }
        }
        "extension_ui_request" => match string(f, "method") {
            "select" | "confirm" | "input" | "editor" => {
                let title = string(f, "title");
                let message = string(f, "message");
                u.status = Some("needs_input");
                u.activity = Some("Waiting for you".into());
                u.attention = Some(Attention {
                    id: string(f, "id").into(),
                    kind: string(f, "method").into(),
                    text: if message.is_empty() {
                        title.into()
                    } else {
                        format!("{title}\n{message}")
                    },
                    options: f["options"]
                        .as_array()
                        .map(|a| {
                            a.iter()
                                .filter_map(Value::as_str)
                                .map(str::to_owned)
                                .collect()
                        })
                        .unwrap_or_default(),
                });
            }
            "cancel" => {
                u.clear_attention = true;
                u.status = Some("running");
                u.activity = Some("Thinking".into());
            }
            "notify" => u.item = Some(item("notice", string(f, "message"), "")),
            "setStatus" => u.activity = Some(string(f, "statusText").into()),
            _ => {}
        },
        "subagent_lifecycle" | "subagent_progress" => {
            let p = &f["payload"];
            u.item = Some(item(
                "subagent",
                format!("Subagent · {}", string(p, "status")),
                p.to_string(),
            ));
        }
        "auto_retry_start" => u.activity = Some("Retrying".into()),
        "auto_compaction_start" => u.activity = Some("Compacting context".into()),
        _ => {}
    }
    u
}
pub fn timeline_payload(session_id: &str, item: &TimelineItem) -> Value {
    json!({"sessionId":session_id,"item":item})
}
