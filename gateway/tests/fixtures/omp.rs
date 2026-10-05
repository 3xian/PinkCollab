// Deterministic subprocess fixture. This binary is excluded from normal builds.
use serde_json::{Value, json};
use std::io::{BufRead, Write};
fn emit(value: Value) {
    println!("{value}");
    std::io::stdout().flush().unwrap();
}
/// Writes an endless unterminated line for 20 seconds: enough for a reader that buffers without a
/// bound to keep growing, while a bounded one rejects the frame immediately.
fn flood_stdout() -> ! {
    let chunk = vec![b'x'; 64 * 1024];
    let mut out = std::io::stdout();
    for _ in 0..2000 {
        if out.write_all(&chunk).is_err() {
            break;
        }
        let _ = out.flush();
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    std::process::exit(1);
}
#[allow(clippy::zombie_processes)] // The fixture deliberately leaves its child for the Gateway job to reap.
fn spawn_lingering_child(cwd: &std::path::Path) {
    let child = std::process::Command::new(std::env::current_exe().unwrap())
        .arg("--linger-child")
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .unwrap();
    std::fs::write(cwd.join("child.pid"), child.id().to_string()).unwrap();
}
fn finish(text: &str, log: &std::path::Path, parent: &mut String) {
    let id = pinkcollab_gateway::storage::id("message_");
    let message = json!({"role":"assistant","timestamp":chrono::Utc::now().timestamp_millis(),"content":[{"type":"text","text":text}],"stopReason":"stop"});
    let entry = json!({"id":id,"parentId":parent,"type":"message","timestamp":chrono::Utc::now(),"message":message});
    writeln!(
        std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log)
            .unwrap(),
        "{entry}"
    )
    .unwrap();
    *parent = id.clone();
    emit(json!({"type":"message_start","messageId":format!("rpc-{id}"),"message":message}));
    if text.starts_with("stream-fixture:") {
        for chunk in text.as_bytes().chunks(2048) {
            emit(
                json!({"type":"message_update","messageId":format!("rpc-{id}"),"assistantMessageEvent":{"type":"text_delta","delta":std::str::from_utf8(chunk).unwrap()}}),
            );
            std::thread::sleep(std::time::Duration::from_millis(75));
        }
    } else {
        emit(
            json!({"type":"message_update","messageId":format!("rpc-{id}"),"assistantMessageEvent":{"type":"text_delta","delta":text}}),
        );
    }
    emit(json!({"type":"message_end","messageId":format!("rpc-{id}"),"message":message}));
    emit(json!({"type":"agent_end"}));
}
fn save_title(log: &std::path::Path, title: &str) {
    let content = std::fs::read_to_string(log).unwrap_or_default();
    let body = if content
        .lines()
        .next()
        .and_then(|line| serde_json::from_str::<Value>(line).ok())
        .is_some_and(|v| v["type"] == "title")
    {
        content
            .split_once('\n')
            .map(|(_, body)| body)
            .unwrap_or_default()
    } else {
        &content
    };
    std::fs::write(
        log,
        format!("{}\n{body}", json!({"type":"title","v":1,"title":title})),
    )
    .unwrap();
}

fn save_tool(log: &std::path::Path, parent: &mut String, call_id: &str) {
    for message in [
        json!({"role":"assistant","content":[{"type":"toolCall","id":call_id,"name":"bash","arguments":{"command":"cargo test"}}]}),
        json!({"role":"toolResult","toolCallId":call_id,"toolName":"bash","content":[{"type":"text","text":"tests passed"}]}),
    ] {
        let id = pinkcollab_gateway::storage::id("message_");
        writeln!(std::fs::OpenOptions::new().create(true).append(true).open(log).unwrap(), "{}",
            json!({"id":id,"parentId":parent,"type":"message","timestamp":chrono::Utc::now(),"message":message})).unwrap();
        *parent = id;
    }
}
fn main() {
    let args = std::env::args().collect::<Vec<_>>();
    if args.get(1).map(String::as_str) == Some("usage") {
        emit(
            json!({"generatedAt":1000,"reports":[{"provider":"fixture", "fetchedAt":900,
            "metadata":{"email":"private@example.com","planType":"Pro"},
            "limits":[{"id":"weekly","label":"Weekly","amount":{"usedFraction":0.62},
                "window":{"resetsAt":2000},"scope":{"shared":true}}]}],
            "accountsWithoutUsage":[{"provider":"other","accountId":"private-id"}]}),
        );
        return;
    }
    if args.iter().any(|arg| arg == "--linger-child") {
        std::thread::sleep(std::time::Duration::from_secs(300));
        return;
    }
    if args.get(1).map(String::as_str) == Some("config")
        && args.get(2).map(String::as_str) == Some("list")
    {
        let project = std::fs::read(std::env::current_dir().unwrap().join(".omp/config.yml"))
            .ok()
            .and_then(|bytes| serde_yaml::from_slice::<Value>(&bytes).ok())
            .unwrap_or_else(|| json!({}));
        let roles = project
            .get("modelRoles")
            .cloned()
            .unwrap_or_else(|| json!({"default":"fixture/fast","smart":"fixture/smart"}));
        let cycle = project
            .get("cycleOrder")
            .cloned()
            .unwrap_or_else(|| json!(["default", "smart"]));
        let providers = project
            .get("modelProviderOrder")
            .cloned()
            .unwrap_or_else(|| json!([]));
        emit(json!({
            "modelRoles":{"value":roles},
            "cycleOrder":{"value":cycle},
            "modelProviderOrder":{"value":providers}
        }));
        return;
    }
    // `--flood-stdout` never finishes a line, which is how a bounded reader is told apart from one
    // that grows a buffer until OMP stops writing.
    if args.iter().any(|arg| arg == "--flood-stdout") {
        flood_stdout();
    }
    if args.iter().any(|arg| arg == "--spawn-child-immediate") {
        spawn_lingering_child(&std::env::current_dir().unwrap());
    } else if args.iter().any(|arg| arg == "--spawn-child") {
        let cwd = std::env::current_dir().unwrap();
        while !cwd.join("job-ready").exists() {
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        spawn_lingering_child(&cwd);
    }
    let mut log =
        std::env::current_dir()
            .unwrap()
            .join(if args.iter().any(|arg| arg == "--lazy-history") {
                format!("fixture-session-{}.jsonl", std::process::id())
            } else {
                "fixture-session.jsonl".into()
            });
    if !args.iter().any(|arg| arg == "--lazy-history") {
        std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&log)
            .unwrap();
    }
    let mut parent = String::new();
    let mut session_id = "fixture".to_owned();
    let all_models = [
        json!({"provider":"fixture","id":"fast","name":"Fixture Fast","reasoning":true,
            "thinking":{"mode":"effort","efforts":["low","high"]}}),
        json!({"provider":"fixture","id":"smart","name":"Fixture Smart","reasoning":true,
            "thinking":{"mode":"effort","efforts":["medium","high"]}}),
    ];
    // `--single-model` mirrors a host whose model scope has no alternative for `cycle_model` to pick.
    let models: &[Value] = if args.iter().any(|arg| arg == "--single-model") {
        &all_models[..1]
    } else {
        &all_models
    };
    let mut model_index = 0;
    let mut thinking_level = "medium".to_owned();
    let mut fast_mode = false;
    let title_file = std::env::current_dir().unwrap().join("fixture-title.txt");
    let mut session_title = std::fs::read_to_string(&title_file).unwrap_or_default();
    let mut pending_prompt_id: Option<Value> = None;
    let mut pending_prompt_ack: Option<Value> = None;
    emit(json!({"type":"ready","protocolVersion":1}));
    if args.iter().any(|arg| arg == "--attention-on-start") {
        emit(
            json!({"type":"extension_ui_request","id":"question-1","method":"select",
            "title":"Which API?","options":["compatibility","new"]}),
        );
    }
    // `--stall-stdin` announces itself and then never drains its pipe, so a write larger than the
    // pipe buffer stays blocked exactly as a wedged OMP would leave it.
    if args.iter().any(|arg| arg == "--stall-stdin") {
        std::thread::sleep(std::time::Duration::from_secs(300));
        return;
    }
    for line in std::io::stdin().lock().lines() {
        let Ok(line) = line else {
            break;
        };
        let Ok(frame) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        let ack = |data: Value| {
            emit(
                json!({"type":"response","id":frame["id"],"command":frame["type"],"success":true,"data":data}),
            )
        };
        match frame["type"].as_str().unwrap_or_default() {
            "get_state" => ack(
                json!({"sessionFile":log,"sessionId":session_id,"sessionName":session_title,"model":models[model_index],"thinkingLevel":thinking_level,"fastModeEnabled":fast_mode,"fastModeActive":fast_mode,"isSettled":pending_prompt_id.is_none() && !args.iter().any(|arg| arg == "--unknown-execution"),"isStreaming":pending_prompt_id.is_some()}),
            ),
            "get_available_thinking_levels" => {
                let mut levels = vec![json!("off")];
                levels.extend(
                    models[model_index]["thinking"]["efforts"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .cloned(),
                );
                ack(json!({"levels":levels}));
            }
            "switch_session" => {
                if args.iter().any(|arg| arg == "--restore-smart-model") {
                    model_index = 1;
                }
                log = std::path::PathBuf::from(frame["sessionPath"].as_str().unwrap());
                session_id = std::fs::read_to_string(&log)
                    .unwrap_or_default()
                    .lines()
                    .filter_map(|line| serde_json::from_str::<Value>(line).ok())
                    .find(|entry| entry["type"] == "session")
                    .and_then(|entry| entry["id"].as_str().map(str::to_owned))
                    .unwrap_or_else(|| "fixture".into());
                parent = std::fs::read_to_string(&log)
                    .unwrap_or_default()
                    .lines()
                    .rev()
                    .filter_map(|line| serde_json::from_str::<Value>(line).ok())
                    .find_map(|entry| entry["id"].as_str().map(str::to_owned))
                    .unwrap_or_default();
                ack(json!({"cancelled":false}));
            }
            "cycle_model" => {
                if models.len() == 1 {
                    ack(Value::Null)
                } else {
                    model_index = (model_index + 1) % models.len();
                    ack(
                        json!({"model":models[model_index],"thinkingLevel":"medium","isScoped":true}),
                    );
                }
            }
            "get_available_models" => ack(json!({"models":models})),
            "set_model" => {
                let selected = models.iter().position(|model| {
                    model["provider"] == frame["provider"] && model["id"] == frame["modelId"]
                });
                if let Some(selected) = selected {
                    model_index = selected;
                    ack(models[model_index].clone());
                    emit(json!({"type":"model_changed"}));
                } else {
                    emit(
                        json!({"type":"response","id":frame["id"],"command":frame["type"],"success":false,"error":"model not found"}),
                    );
                }
            }
            "set_thinking_level" => {
                thinking_level = frame["level"].as_str().unwrap_or("medium").to_owned();
                ack(json!({}));
            }
            "set_fast_mode" => {
                fast_mode = frame["enabled"].as_bool().unwrap();
                ack(json!({"enabled":fast_mode,"active":fast_mode}));
            }
            "prompt" => {
                if args.iter().any(|arg| arg == "--record-prompt-frame") {
                    std::fs::write(std::env::current_dir().unwrap().join("last-prompt.json"), {
                        let mut recorded = frame.clone();
                        recorded["fixtureModelId"] = models[model_index]["id"].clone();
                        recorded.to_string()
                    })
                    .unwrap();
                }
                let message = frame["message"].as_str().unwrap_or_default();
                if let Some(title) = message.strip_prefix("silent-title:") {
                    session_title = title.to_owned();
                    std::fs::write(&title_file, &session_title).unwrap();
                    save_title(&log, &session_title);
                }
                if let Some(title) = message.strip_prefix("title:") {
                    session_title = title.to_owned();
                    std::fs::write(&title_file, &session_title).unwrap();
                    save_title(&log, &session_title);
                    emit(
                        json!({"type":"session_info_update","title":session_title,"sessionId":"fixture"}),
                    );
                }
                if let Some(title) = message.strip_prefix("stale-title:") {
                    session_title = title.to_owned();
                    std::fs::write(&title_file, &session_title).unwrap();
                    save_title(&log, &session_title);
                    emit(json!({"type":"session_info_update","title":"Stale event title"}));
                }
                if message == "fail" {
                    emit(
                        json!({"type":"response","id":frame["id"],"success":false,"error":"fixture rejects prompt"}),
                    );
                    continue;
                }
                let id = pinkcollab_gateway::storage::id("message_");
                writeln!(std::fs::OpenOptions::new().create(true).append(true).open(&log).unwrap(),"{}",json!({"id":id,"parentId":parent,"type":"message","timestamp":chrono::Utc::now(),"message":{"role":"user","content":[{"type":"text","text":message}]}})).unwrap();
                parent = id.clone();
                if message == "need input before ack" {
                    pending_prompt_ack = frame.get("id").cloned();
                } else if message != "settle before ack" {
                    ack(json!({}));
                }
                emit(
                    json!({"type":"message_end","messageId":id,"message":{"role":"user","content":[{"type":"text","text":message}]}}),
                );
                emit(json!({"type":"agent_start"}));
                pending_prompt_id = frame.get("id").cloned();
                let request = match message {
                    "need input" | "need input before ack" | "need expired input" => {
                        Some(("select", "Which API?"))
                    }
                    "need confirm" => Some(("confirm", "Continue?")),
                    "need text" => Some(("input", "What should I write?")),
                    "need editor" => Some(("editor", "Edit the note")),
                    _ => None,
                };
                if let Some((method, title)) = request {
                    emit(
                        json!({"type":"extension_ui_request","id":"question-1","method":method,
                        "title":title,"options":["compatibility","new"]}),
                    );
                    if message == "need expired input" {
                        emit(
                            json!({"type":"extension_ui_request","method":"cancel","targetId":"question-1"}),
                        );
                        finish("Input expired", &log, &mut parent);
                        emit(
                            json!({"type":"prompt_result","id":frame["id"],"agentInvoked":true,
                            "status":"completed","sessionSettled":true}),
                        );
                        pending_prompt_id = None;
                    } else if args.iter().any(|arg| arg == "--stall-input-on-attention") {
                        std::thread::sleep(std::time::Duration::from_secs(300));
                    }
                } else if message == "hold" {
                } else if message == "steer" {
                    if frame["streamingBehavior"] != "steer" {
                        emit(
                            json!({"type":"prompt_result","success":false,"error":"missing streamingBehavior"}),
                        );
                    } else {
                        finish("Steering accepted", &log, &mut parent);
                        emit(
                            json!({"type":"prompt_result","id":frame["id"],"agentInvoked":true,"status":"completed","sessionSettled":true}),
                        );
                        pending_prompt_id = None;
                    }
                } else {
                    let call_id = format!("{id}-tool");
                    emit(
                        json!({"type":"tool_execution_start","toolCallId":call_id,"toolName":"bash","args":{"command":"cargo test"}}),
                    );
                    emit(
                        json!({"type":"tool_execution_update","toolCallId":call_id,"toolName":"bash","args":{"command":"cargo test"},"partialResult":{"content":[{"type":"text","text":"running tests"}]}}),
                    );
                    emit(
                        json!({"type":"tool_execution_end","toolCallId":call_id,"toolName":"bash","result":{"content":[{"type":"text","text":"tests passed"}]}}),
                    );
                    save_tool(&log, &mut parent, &call_id);
                    if message == "todo snapshot" {
                        emit(
                            json!({"type":"tool_execution_start","toolCallId":"todo-1","toolName":"todo","args":{"op":"init"}}),
                        );
                        emit(
                            json!({"type":"tool_execution_end","toolCallId":"todo-1","toolName":"todo","result":{
                                "content":[{"type":"text","text":"x".repeat(10 * 1024)}],
                                "details":{"phases":[{"name":"Ship","tasks":[{"content":"Verify (dropped)","status":"pending"}]}]}
                            }}),
                        );
                    }
                    let reply = if message == "stream reply" {
                        format!("stream-fixture:{}", "abcdef0123456789".repeat(8192))
                    } else if message == "long reply" {
                        "完整回复。".repeat(20_000)
                    } else {
                        "Task complete".into()
                    };
                    finish(&reply, &log, &mut parent);
                    emit(
                        json!({"type":"prompt_result","id":frame["id"],"agentInvoked":true,"status":"completed","sessionSettled":true}),
                    );
                    pending_prompt_id = None;
                    if message == "settle before ack" {
                        // Let the Gateway consume the terminal event before the command's
                        // response waiter resumes, reproducing the late-ack ordering.
                        std::thread::sleep(std::time::Duration::from_millis(200));
                        ack(json!({}));
                    }
                }
            }
            "abort" => {
                ack(json!({}));
                emit(json!({"type":"agent_end"}));
                if let Some(id) = pending_prompt_id.take() {
                    emit(
                        json!({"type":"prompt_result","id":id,"agentInvoked":true,"status":"aborted","sessionSettled":true}),
                    );
                }
            }
            "extension_ui_response" => {
                std::fs::write(
                    std::env::current_dir()
                        .unwrap()
                        .join("last-input-response.json"),
                    frame.to_string(),
                )
                .unwrap();
                if args.iter().any(|arg| arg == "--ignore-input-response") {
                    continue;
                }
                if let Some(id) = pending_prompt_ack.take() {
                    emit(
                        json!({"type":"response","id":id,"command":"prompt","success":true,"data":{}}),
                    );
                }
                // OMP can also switch models on its own; announce it so the Gateway re-reads its state.
                model_index = (model_index + 1) % models.len();
                emit(json!({"type":"model_changed"}));
                finish("Answer received", &log, &mut parent);
                if let Some(id) = pending_prompt_id.take() {
                    emit(
                        json!({"type":"prompt_result","id":id,"agentInvoked":true,"status":"completed","sessionSettled":true}),
                    );
                }
            }
            _ => ack(json!({})),
        }
    }
    if args.iter().any(|arg| arg == "--linger-on-eof") {
        std::thread::sleep(std::time::Duration::from_secs(300));
    }
}
