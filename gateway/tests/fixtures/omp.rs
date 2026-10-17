// Deterministic subprocess fixture. This binary is excluded from normal builds.
use base64::{Engine as _, engine::general_purpose::STANDARD};
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
fn save_message(log: &std::path::Path, parent: &mut String, message: &Value) -> String {
    let id = pinkcollab_gateway::storage::id("message_");
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
    id
}
fn finish(text: &str, log: &std::path::Path, parent: &mut String) {
    let message = json!({
        "role":"assistant","timestamp":chrono::Utc::now().timestamp_millis(),
        "content":[{"type":"text","text":text}],"stopReason":"stop",
        "usage":{"output":120,"input":9000},"duration":2500,"ttft":500
    });
    let id = save_message(log, parent, &message);
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

fn large_models(models: &[Value]) -> Vec<Value> {
    let mut result = models.to_vec();
    result.extend((0..640).map(|index| json!({
        "provider":"fixture","id":format!("large-{index}"),"name":format!("Fixture Large {index}"),
        "reasoning":true,"contextWindow":200000,"maxTokens":8192,
        "cost":{"input":1.25,"output":5,"cacheRead":0.1,"cacheWrite":1.5},
        "input":["text","image"],"thinking":{"mode":"effort","efforts":["low","medium","high"]},
        "fixtureMetadata":{"index":index,"description":"界".repeat(700)}
    })));
    result
}

fn option<'a>(args: &'a [String], name: &str) -> Option<&'a str> {
    args.windows(2)
        .find(|pair| pair[0] == name)
        .map(|pair| pair[1].as_str())
}

fn chunk_frames(bytes: &[u8]) -> Vec<Value> {
    // An odd byte boundary intentionally bisects multibyte UTF-8 codepoints.
    let size = 200_003;
    let count = bytes.len().div_ceil(size);
    bytes
        .chunks(size)
        .enumerate()
        .map(|(index, part)| {
            json!({
                "type":"rpc_chunk","chunkId":"fixture-chunks","index":index,
                "count":count,"byteLength":bytes.len(),"data":STANDARD.encode(part)
            })
        })
        .collect()
}

fn emit_chunked(value: Value) {
    let bytes = serde_json::to_vec(&value).unwrap();
    let frames = chunk_frames(&bytes);
    assert!(frames.len() >= 5, "fixture must exercise a long sequence");
    assert!(
        bytes
            .chunks(200_003)
            .any(|part| std::str::from_utf8(part).is_err()),
        "fixture must split a UTF-8 codepoint"
    );
    for frame in frames {
        emit(frame);
    }
}

fn malformed(mode: &str, request: &Value) {
    if matches!(
        mode,
        "physical-local" | "physical-advertised" | "unterminated-local" | "unterminated-advertised"
    ) {
        let size = if mode.ends_with("advertised") {
            65_537
        } else {
            1_048_577
        };
        let mut stdout = std::io::stdout().lock();
        if mode.starts_with("unterminated") {
            stdout.write_all(&vec![b'x'; size]).unwrap();
        } else {
            let bytes =
                serde_json::to_vec(&json!({"type":"fixture_event","text":"x".repeat(size)}))
                    .unwrap();
            stdout.write_all(&bytes).unwrap();
        }
        if !mode.starts_with("unterminated") {
            stdout.write_all(b"\n").unwrap();
        }
        stdout.flush().unwrap();
        std::thread::sleep(std::time::Duration::from_secs(30));
        return;
    }
    let value = json!({"type":"response","id":request["id"],"success":true,"data":{"text":"界🙂".repeat(180_000)}});
    let mut bytes = serde_json::to_vec(&value).unwrap();
    match mode {
        "invalid-json" => bytes = vec![b'x'; 1_200_000],
        "invalid-utf8" => bytes = vec![0xff; 1_200_000],
        "non-object" => bytes = serde_json::to_vec(&json!(["x".repeat(1_200_000)])).unwrap(),
        "nested-chunk" => bytes = serde_json::to_vec(&json!({"type":"rpc_chunk","text":"x".repeat(1_200_000)})).unwrap(),
        "oversized-chunk" => bytes = serde_json::to_vec(&json!({"type":"response","id":request["id"],"success":true,"data":{"text":"x".repeat(1_200_000)}})).unwrap(),
        _ => {}
    }
    let mut frames = chunk_frames(&bytes);
    if mode == "oversized-chunk" {
        let split = 256 * 1024 + 1;
        let parts = std::iter::once(&bytes[..split])
            .chain(bytes[split..].chunks(200_003))
            .collect::<Vec<_>>();
        let count = parts.len();
        frames = parts
            .into_iter()
            .enumerate()
            .map(|(index, part)| {
                json!({
                    "type":"rpc_chunk","chunkId":"fixture-chunks","index":index,
                    "count":count,"byteLength":bytes.len(),"data":STANDARD.encode(part)
                })
            })
            .collect();
    }
    match mode {
        "wrong-index" => frames[0]["index"] = json!(1),
        "wrong-id" => frames[1]["chunkId"] = json!("different"),
        "wrong-count" => frames[1]["count"] = json!(99),
        "duplicate" => {
            frames.insert(1, frames[0].clone());
        }
        "missing" => {
            frames.remove(1);
        }
        "interleaved" => {
            frames.insert(1, json!({"type":"fixture_event","text":"interleaved"}));
        }
        "short-length" => {
            for frame in &mut frames {
                frame["byteLength"] = json!(bytes.len() - 1);
            }
        }
        "long-length" => {
            for frame in &mut frames {
                frame["byteLength"] = json!(bytes.len() + 1);
            }
        }
        "small-length" => frames[0]["byteLength"] = json!(100),
        "corrupt-base64" => frames[0]["data"] = json!("%%%="),
        "unpadded-base64" => frames[0]["data"] = json!("YQ"),
        "noncanonical-base64" => frames[0]["data"] = json!("YR=="),
        "oversized-reassembly" => frames[0]["byteLength"] = json!(64 * 1024 * 1024 + 1),
        "count-one" => frames[0]["count"] = json!(1),
        "count-too-many" => frames[0]["count"] = json!(257),
        "empty-id" => frames[0]["chunkId"] = json!(""),
        "long-id" => frames[0]["chunkId"] = json!("x".repeat(129)),
        _ => {}
    }
    for (index, frame) in frames.into_iter().enumerate() {
        emit(frame);
        if mode == "midchunk-exit" && index == 0 {
            std::process::exit(7);
        }
        if mode == "missing-stall" && index == 0 {
            std::thread::sleep(std::time::Duration::from_secs(30));
            return;
        }
    }
    // Leave the process alive so transport failures cannot be mistaken for fixture EOF.
    std::thread::sleep(std::time::Duration::from_secs(30));
}

fn save_tool(log: &std::path::Path, parent: &mut String, call_id: &str) {
    for message in [
        json!({"role":"assistant","content":[{"type":"toolCall","id":call_id,"name":"bash","arguments":{"command":"cargo test"}}]}),
        json!({"role":"toolResult","toolCallId":call_id,"toolName":"bash","content":[{"type":"text","text":"tests passed"}]}),
    ] {
        save_message(log, parent, &message);
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
    let expanded_models;
    // `--single-model` mirrors a host whose model scope has no alternative for `cycle_model` to pick.
    let models: &[Value] = if args.iter().any(|arg| arg == "--large-models") {
        expanded_models = large_models(&all_models);
        &expanded_models
    } else if args.iter().any(|arg| arg == "--single-model") {
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
    let v2 = args.iter().any(|arg| arg == "--rpc-v2");
    let mode = option(&args, "--rpc-case").unwrap_or_default();
    let physical =
        if mode.ends_with("advertised") || args.iter().any(|arg| arg == "--physical-small") {
            65_536
        } else if mode.ends_with("local") || args.iter().any(|arg| arg == "--physical-large") {
            2 * 1024 * 1024
        } else {
            1024 * 1024
        };
    let reassembly =
        if mode == "advertised-reassembly" || args.iter().any(|arg| arg == "--reassembly-small") {
            1_100_000
        } else if mode == "oversized-reassembly" {
            128 * 1024 * 1024
        } else {
            64 * 1024 * 1024
        };
    let mut negotiated = false;
    if v2 {
        emit(
            json!({"type":"ready","protocolVersion":1,"supportedProtocolVersions":[1,2],
            "maxFrameBytes":physical,"maxReassembledFrameBytes":reassembly}),
        );
    } else {
        emit(json!({"type":"ready","protocolVersion":1}));
    }
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
        if frame["type"] == "negotiate_protocol" {
            assert!(
                v2 && frame["id"] == "protocol-1" && frame["protocolVersion"] == 2,
                "unexpected negotiation"
            );
            match option(&args, "--negotiation") {
                Some("timeout") => {
                    std::thread::sleep(std::time::Duration::from_secs(30));
                    continue;
                }
                Some("exit") => std::process::exit(8),
                Some("reject") => emit(
                    json!({"type":"response","id":frame["id"],"success":false,"error":"fixture rejected negotiation"}),
                ),
                Some("wrong-version") => emit(
                    json!({"type":"response","id":frame["id"],"success":true,"data":{"protocolVersion":1}}),
                ),
                _ => {
                    negotiated = true;
                    emit(
                        json!({"type":"response","id":frame["id"],"command":"negotiate_protocol","success":true,"data":{"protocolVersion":2}}),
                    );
                }
            }
            continue;
        }
        if v2 && !negotiated {
            eprintln!("business call arrived before negotiation");
            std::process::exit(9);
        }
        if frame["type"] == "fixture_malformed" {
            malformed(mode, &frame);
            continue;
        }
        if frame["type"] == "fixture_large_event" {
            emit_chunked(json!({"type":"fixture_event","text":"界🙂".repeat(240_000)}));
        }
        if frame["type"] == "fixture_echo" {
            emit(json!({"type":"response","id":frame["id"],"success":true,
                "data":{"sequence":frame["sequence"],"negotiated":negotiated}}));
            continue;
        }
        let ack = |data: Value| {
            let reply = json!({"type":"response","id":frame["id"],"command":frame["type"],"success":true,"data":data});
            if serde_json::to_vec(&reply).unwrap().len() >= 1024 * 1024 {
                if v2 {
                    emit_chunked(reply);
                } else {
                    emit(
                        json!({"type":"response","id":frame["id"],"command":frame["type"],
                        "success":false,"error":"RPC response exceeded the transport limit"}),
                    );
                }
            } else {
                emit(reply);
            }
        };
        match frame["type"].as_str().unwrap_or_default() {
            "get_state" => {
                if args.iter().any(|arg| arg == "--startup-event-burst") {
                    for _ in 0..3 {
                        emit(json!({"type":"available_commands_update","commands":[]}));
                    }
                }
                ack(
                    json!({"sessionFile":log,"sessionId":session_id,"sessionName":session_title,"model":models[model_index],"thinkingLevel":thinking_level,"fastModeEnabled":fast_mode,"fastModeActive":fast_mode,"isSettled":pending_prompt_id.is_none() && !args.iter().any(|arg| arg == "--unknown-execution"),"isStreaming":pending_prompt_id.is_some()}),
                );
            }
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
                } else if message == "token speed" || message == "invalid token speed" {
                    let assistant = json!({
                        "role":"assistant","timestamp":chrono::Utc::now().timestamp_millis(),
                        "content":[],"usage":{"output":120,"input":9000},
                        "duration":2500,"ttft":500
                    });
                    emit(
                        json!({"type":"message_end","messageId":format!("{id}-speed"),"message":assistant}),
                    );
                    save_message(&log, &mut parent, &assistant);
                    if message == "invalid token speed" {
                        // A later assistant completion in the same round must clear the sample.
                        let missing = json!({"role":"assistant","content":[],"duration":2500});
                        emit(
                            json!({"type":"message_end","messageId":format!("{id}-missing"),"message":missing}),
                        );
                        save_message(&log, &mut parent, &missing);
                    }
                    emit(json!({"type":"agent_end"}));
                    emit(
                        json!({"type":"prompt_result","id":frame["id"],"agentInvoked":true,"status":"completed","sessionSettled":true}),
                    );
                    pending_prompt_id = None;
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
                } else if message == "tool intents" {
                    let calls = [
                        json!({"type":"toolCall","id":"intent-read","name":"read","arguments":{"i":"Inspecting session state","path":"src/Session.kt"}}),
                        json!({"type":"toolCall","id":"intent-edit","name":"edit","arguments":{"i":" ","description":"Updating session projection","input":"supplied patch"}}),
                    ];
                    // Read metadata precedes execution; edit metadata arrives after its result.
                    // Execution arguments deliberately omit model-supplied intent.
                    emit(
                        json!({"type":"tool_execution_end","toolCallId":"intent-edit","toolName":"edit","isError":true,"result":{"content":[{"type":"text","text":"patch rejected"}]}}),
                    );
                    let assistant = json!({"role":"assistant","content":calls});
                    emit(
                        json!({"type":"message_end","messageId":"intent-message","message":assistant}),
                    );
                    emit(
                        json!({"type":"tool_execution_start","toolCallId":"intent-read","toolName":"read","args":{"path":"src/Session.kt"}}),
                    );
                    emit(
                        json!({"type":"tool_execution_update","toolCallId":"intent-read","toolName":"read","args":{"path":"src/Session.kt"},"partialResult":{"content":[{"type":"text","text":"reading"}]}}),
                    );
                    emit(
                        json!({"type":"tool_execution_end","toolCallId":"intent-read","toolName":"read","result":{"content":[{"type":"text","text":"session content"}]}}),
                    );
                    for saved in [
                        assistant,
                        json!({"role":"toolResult","toolCallId":"intent-read","toolName":"read","content":[{"type":"text","text":"session content"}]}),
                        json!({"role":"toolResult","toolCallId":"intent-edit","toolName":"edit","isError":true,"content":[{"type":"text","text":"patch rejected"}]}),
                    ] {
                        save_message(&log, &mut parent, &saved);
                    }
                    finish("Intent fixture complete", &log, &mut parent);
                    emit(
                        json!({"type":"prompt_result","id":frame["id"],"agentInvoked":true,"status":"completed","sessionSettled":true}),
                    );
                    pending_prompt_id = None;
                } else if message == "late tool intents" {
                    let mut calls: Vec<Value> = (0..65).map(|index| json!({
                        "type":"toolCall","id":format!("late-{index}"),"name":"read",
                        "arguments":{"i":format!("Inspecting file {index}"),"path":format!("file-{index}")}
                    })).collect();
                    calls.push(json!({"type":"toolCall","id":"never-started","name":"read","arguments":{"i":"Unexecuted declaration","path":"not-read"}}));
                    let assistant = json!({"role":"assistant","content":calls});
                    save_message(&log, &mut parent, &assistant);
                    for index in 0..65 {
                        let result = json!({"content":[{"type":"text","text":format!("completed-{index}")}]});
                        let call_id = format!("late-{index}");
                        emit(
                            json!({"type":"tool_execution_end","toolCallId":call_id,"toolName":"read","result":result}),
                        );
                        save_message(
                            &log,
                            &mut parent,
                            &json!({
                                "role":"toolResult","toolCallId":call_id,"toolName":"read","content":result["content"]
                            }),
                        );
                    }
                    emit(
                        json!({"type":"message_end","messageId":"late-intent-message","message":assistant}),
                    );
                    finish("Late intent fixture complete", &log, &mut parent);
                    emit(
                        json!({"type":"prompt_result","id":frame["id"],"agentInvoked":true,"status":"completed","sessionSettled":true}),
                    );
                    pending_prompt_id = None;
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
