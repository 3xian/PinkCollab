// Deterministic subprocess fixture. This binary is excluded from normal builds.
use serde_json::{Value, json};
use std::io::{BufRead, Write};
fn emit(value: Value) {
    println!("{value}");
    std::io::stdout().flush().unwrap();
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
    let log = std::env::current_dir()
        .unwrap()
        .join("fixture-session.jsonl");
    let mut parent = String::new();
    emit(json!({"type":"ready","protocolVersion":1}));
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
            "get_state" => ack(json!({"sessionFile":log,"sessionId":"fixture"})),
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
            "extension_ui_response" => finish("Answer received", &log, &mut parent),
            _ => ack(json!({})),
        }
    }
}
