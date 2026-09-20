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
fn finish(text: &str, log: &std::path::Path, parent: &mut String) {
    let id = pinkcollab_gateway::storage::id("message_");
    let message =
        json!({"role":"assistant","content":[{"type":"text","text":text}],"stopReason":"stop"});
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
    *parent = id;
    emit(
        json!({"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":text}}),
    );
    emit(json!({"type":"message_end","message":message}));
    emit(json!({"type":"agent_end"}));
}
fn main() {
    let args = std::env::args().collect::<Vec<_>>();
    // `--flood-stdout` never finishes a line, which is how a bounded reader is told apart from one
    // that grows a buffer until OMP stops writing.
    if args.iter().any(|arg| arg == "--flood-stdout") {
        flood_stdout();
    }
    let log = std::env::current_dir()
        .unwrap()
        .join("fixture-session.jsonl");
    let mut parent = String::new();
    let all_models = [
        json!({"provider":"fixture","id":"fast","name":"Fixture Fast"}),
        json!({"provider":"fixture","id":"smart","name":"Fixture Smart"}),
    ];
    // `--single-model` mirrors a host whose model scope has no alternative for `cycle_model` to pick.
    let models: &[Value] = if args.iter().any(|arg| arg == "--single-model") {
        &all_models[..1]
    } else {
        &all_models
    };
    let mut model_index = 0;
    emit(json!({"type":"ready","protocolVersion":1}));
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
            "get_state" => {
                ack(json!({"sessionFile":log,"sessionId":"fixture","model":models[model_index]}))
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
            "prompt" => {
                let message = frame["message"].as_str().unwrap_or_default();
                if message == "fail" {
                    emit(
                        json!({"type":"response","id":frame["id"],"success":false,"error":"fixture rejects prompt"}),
                    );
                    continue;
                }
                let id = pinkcollab_gateway::storage::id("message_");
                writeln!(std::fs::OpenOptions::new().create(true).append(true).open(&log).unwrap(),"{}",json!({"id":id,"parentId":parent,"type":"message","timestamp":chrono::Utc::now(),"message":{"role":"user","content":[{"type":"text","text":message}]}})).unwrap();
                parent = id;
                ack(json!({}));
                emit(json!({"type":"agent_start"}));
                if message == "need input" {
                    emit(
                        json!({"type":"extension_ui_request","id":"question-1","method":"select","title":"Which API?","options":["compatibility","new"]}),
                    );
                } else if message == "hold" {
                } else if message == "steer" {
                    if frame["streamingBehavior"] != "steer" {
                        emit(
                            json!({"type":"prompt_result","success":false,"error":"missing streamingBehavior"}),
                        );
                    } else {
                        finish("Steering accepted", &log, &mut parent);
                    }
                } else {
                    emit(
                        json!({"type":"tool_execution_start","toolCallId":"tool-1","toolName":"bash","args":{"command":"cargo test"}}),
                    );
                    emit(
                        json!({"type":"tool_execution_end","toolCallId":"tool-1","toolName":"bash","result":{"content":[{"type":"text","text":"tests passed"}]}}),
                    );
                    finish("Task complete", &log, &mut parent);
                }
            }
            "abort" => {
                ack(json!({}));
                emit(json!({"type":"agent_end"}));
            }
            "extension_ui_response" => {
                // OMP can also switch models on its own; announce it so the Gateway re-reads its state.
                model_index = (model_index + 1) % models.len();
                emit(json!({"type":"model_changed"}));
                finish("Answer received", &log, &mut parent);
            }
            _ => ack(json!({})),
        }
    }
}
