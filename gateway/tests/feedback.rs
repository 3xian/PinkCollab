#![cfg(feature = "test-fixtures")]
mod support;

use chrono::Utc;
use pinkcollab_gateway::{events::Event, protocol::ServerEvent};
use serde_json::{Value, json};
use std::time::Duration;
use support::{
    Harness, command_succeeds, create_session, runtime_generation, wait_operation, wait_runtime,
};

async fn get(client: &reqwest::Client, url: &str, credential: &str) -> Value {
    client
        .get(url)
        .bearer_auth(credential)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap()
}

async fn post(client: &reqwest::Client, session: &str, credential: &str, body: &Value) -> Value {
    let response = client
        .post(format!("{session}/commands"))
        .bearer_auth(credential)
        .json(body)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 202);
    response.json().await.unwrap()
}

async fn terminal(
    client: &reqwest::Client,
    session: &str,
    credential: &str,
    command: &str,
) -> Value {
    tokio::time::timeout(Duration::from_secs(25), async {
        loop {
            let receipt = get(
                client,
                &format!("{session}/operations/{command}"),
                credential,
            )
            .await;
            if receipt["state"] != "pending" {
                return receipt;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap()
}

fn feedback(page: &Value) -> Vec<Value> {
    page["items"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|item| item["kind"] == "feedback")
        .cloned()
        .collect()
}

#[tokio::test]
async fn every_delivered_answer_is_exact_live_once_and_survives_gateway_reopen() {
    let mut h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let cwd = h.cwd("feedback-answers");
    let mut session = create_session(&client, &h, &credential, &cwd).await;
    let id = session.rsplit('/').next().unwrap().to_owned();
    command_succeeds(
        &client,
        &session,
        &credential,
        "start",
        json!({"type":"start_runtime"}),
    )
    .await;
    let generation = runtime_generation(&client, &session, &credential).await;
    let cases = [
        ("need input", json!({"value":"new"}), "Which API?", "new"),
        (
            "need confirm",
            json!({"confirmed":true}),
            "Continue?",
            "Confirmed",
        ),
        (
            "need confirm",
            json!({"confirmed":false}),
            "Continue?",
            "Declined",
        ),
        (
            "need text",
            json!({"value":"  exact 中文🙂\n "}),
            "What should I write?",
            "  exact 中文🙂\n ",
        ),
        (
            "need editor",
            json!({"value":"first line\nsecond line"}),
            "Edit the note",
            "first line\nsecond line",
        ),
        ("need text", json!({"value":""}), "What should I write?", ""),
        (
            "need text",
            json!({"value":" \t\n  "}),
            "What should I write?",
            " \t\n  ",
        ),
        (
            "need input",
            json!({"cancelled":true,"value":"ignored"}),
            "Which API?",
            "Cancelled",
        ),
        (
            "need confirm",
            json!({"cancelled":true,"confirmed":true}),
            "Continue?",
            "Cancelled",
        ),
    ];
    let mut delivered = Vec::new();
    let mut last_command = Value::Null;
    for (index, (prompt, fields, question, answer)) in cases.into_iter().enumerate() {
        let prompt_id = format!("prompt-{index}");
        post(
            &client,
            &session,
            &credential,
            &json!({"commandId":prompt_id,"type":"prompt",
            "generation":generation,"message":prompt}),
        )
        .await;
        wait_runtime(&client, &session, &credential, "waiting_input").await;
        let before = Utc::now();
        let sequence = h.bus.sequence(Some(&id));
        let mut response = json!({"commandId":format!("answer-{index}"),"type":"respond",
            "generation":generation,"inputRequestId":"question-1"});
        response
            .as_object_mut()
            .unwrap()
            .extend(fields.as_object().unwrap().clone());
        post(&client, &session, &credential, &response).await;
        let receipt = terminal(&client, &session, &credential, &format!("answer-{index}")).await;
        assert_eq!(receipt["state"], "succeeded");
        wait_operation(
            &client,
            &format!("{session}/operations/{prompt_id}"),
            &credential,
            "succeeded",
        )
        .await;
        let replay = h.bus.replay(&h.bus.epoch, sequence, Some(&id)).unwrap();
        let live: Vec<_> = replay
            .into_iter()
            .filter_map(|event| {
                if let Event::Update { event, .. } = event
                    && let ServerEvent::Timeline { upsert, .. } = &*event
                {
                    return Some(
                        upsert
                            .iter()
                            .filter(|item| item.kind == "feedback")
                            .map(|item| serde_json::to_value(item).unwrap())
                            .collect::<Vec<_>>(),
                    );
                }
                None
            })
            .flatten()
            .collect();
        assert_eq!(live.len(), 1);
        let item = &live[0];
        assert_eq!(item["detail"], question);
        assert_eq!(item["text"], answer);
        assert_eq!(item["sourceId"], item["id"]);
        assert!(item.get("messageKey").is_none());
        assert!(item.get("tool").is_none());
        let timestamp: chrono::DateTime<Utc> = item["timestamp"].as_str().unwrap().parse().unwrap();
        assert!(timestamp >= before && timestamp <= Utc::now());
        let saved = feedback(&get(&client, &format!("{session}/history"), &credential).await);
        assert_eq!(saved.last(), Some(item));
        let snapshot = get(&client, &session, &credential).await;
        assert!(snapshot["timeline"].as_array().unwrap().contains(item));
        let sent: Value = serde_json::from_slice(
            &std::fs::read(std::path::Path::new(&cwd).join("last-input-response.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(sent["id"], "question-1");
        if fields["cancelled"] == true {
            assert_eq!(sent["cancelled"], true);
            assert!(sent.get("value").is_none() && sent.get("confirmed").is_none());
        } else if prompt == "need confirm" {
            assert_eq!(sent["confirmed"], fields["confirmed"]);
        } else {
            assert_eq!(sent["value"], fields["value"]);
        }
        delivered.push(item.clone());
        last_command = response;
    }
    let page = get(&client, &format!("{session}/history"), &credential).await;
    assert_eq!(feedback(&page), delivered);
    let kinds: Vec<_> = page["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["kind"].as_str().unwrap())
        .collect();
    assert_eq!(
        kinds,
        ["user", "feedback", "assistant"].repeat(delivered.len())
    );
    let sequence = h.bus.sequence(Some(&id));
    let replay = client
        .post(format!("{session}/commands"))
        .bearer_auth(&credential)
        .json(&last_command)
        .send()
        .await
        .unwrap();
    assert_eq!(replay.status(), 200);
    assert_eq!(
        replay.json::<Value>().await.unwrap()["operation"]["state"],
        "succeeded"
    );
    assert_eq!(
        feedback(&get(&client, &format!("{session}/history"), &credential).await),
        delivered
    );
    assert!(h.bus.replay(&h.bus.epoch, sequence, Some(&id)).unwrap().into_iter().all(|event| {
        !matches!(event, Event::Update { event, .. }
            if matches!(&*event, ServerEvent::Timeline { upsert, .. } if upsert.iter().any(|item| item.kind == "feedback")))
    }));
    let mut conflicting = last_command.clone();
    conflicting["cancelled"] = json!(false);
    assert_eq!(
        client
            .post(format!("{session}/commands"))
            .bearer_auth(&credential)
            .json(&conflicting)
            .send()
            .await
            .unwrap()
            .status(),
        409
    );
    command_succeeds(
        &client,
        &session,
        &credential,
        "stop",
        json!({"type":"stop_runtime","generation":generation}),
    )
    .await;
    h.restart(1, vec![]).await;
    session = format!("{}/api/v4/sessions/{id}", h.url);
    let reopened = get(&client, &session, &credential).await;
    assert_eq!(reopened["hasHistory"], true);
    assert!(reopened["runtime"].is_null());
    assert_eq!(
        feedback(&get(&client, &format!("{session}/history"), &credential).await),
        delivered
    );
    // Replaying a receipt after recreating the Gateway must not require the old generation.
    let reopened_replay = client
        .post(format!("{session}/commands"))
        .bearer_auth(&credential)
        .json(&last_command)
        .send()
        .await
        .unwrap();
    assert_eq!(reopened_replay.status(), 200);
    assert_eq!(
        reopened_replay.json::<Value>().await.unwrap()["operation"]["state"],
        "succeeded"
    );
    command_succeeds(
        &client,
        &session,
        &credential,
        "resume",
        json!({"type":"start_runtime"}),
    )
    .await;
    assert_eq!(
        feedback(&get(&client, &format!("{session}/history"), &credential).await),
        delivered
    );
    let resumed = runtime_generation(&client, &session, &credential).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "stop-resumed",
        json!({"type":"stop_runtime","generation":resumed}),
    )
    .await;
}

#[tokio::test]
async fn invalid_expired_and_concurrent_answers_do_not_invent_or_duplicate_feedback() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let session = create_session(&client, &h, &credential, &h.cwd("feedback-rejections")).await;
    post(
        &client,
        &session,
        &credential,
        &json!({"commandId":"prompt","type":"prompt","message":"need input"}),
    )
    .await;
    let waiting = wait_runtime(&client, &session, &credential, "waiting_input").await;
    let generation = waiting["generation"].clone();
    let invalid = json!({"commandId":"invalid","type":"respond","generation":generation,
        "inputRequestId":"question-1","value":"not an option"});
    post(&client, &session, &credential, &invalid).await;
    assert_eq!(
        terminal(&client, &session, &credential, "invalid").await["error"]["code"],
        "invalid_answer"
    );
    assert!(feedback(&get(&client, &format!("{session}/history"), &credential).await).is_empty());
    assert_eq!(
        get(&client, &session, &credential).await["runtime"]["pendingInputs"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    post(
        &client,
        &session,
        &credential,
        &json!({"commandId":"old-generation","type":"respond",
        "generation":"stale","inputRequestId":"question-1","value":"new"}),
    )
    .await;
    assert_eq!(
        terminal(&client, &session, &credential, "old-generation").await["error"]["code"],
        "generation_mismatch"
    );
    let first = json!({"commandId":"first","type":"respond","generation":generation,
        "inputRequestId":"question-1","value":"new"});
    let second = json!({"commandId":"second","type":"respond","generation":generation,
        "inputRequestId":"question-1","value":"compatibility"});
    tokio::join!(
        post(&client, &session, &credential, &first),
        post(&client, &session, &credential, &second)
    );
    let a = terminal(&client, &session, &credential, "first").await;
    let b = terminal(&client, &session, &credential, "second").await;
    assert_eq!(
        [&a, &b]
            .into_iter()
            .filter(|receipt| receipt["state"] == "succeeded")
            .count(),
        1
    );
    let rejected = if a["state"] == "succeeded" { &b } else { &a };
    assert_eq!(rejected["error"]["code"], "input_expired");
    wait_operation(
        &client,
        &format!("{session}/operations/prompt"),
        &credential,
        "succeeded",
    )
    .await;
    assert_eq!(
        feedback(&get(&client, &format!("{session}/history"), &credential).await).len(),
        1
    );
    post(
        &client,
        &session,
        &credential,
        &json!({"commandId":"confirm-prompt","type":"prompt",
        "generation":generation,"message":"need confirm"}),
    )
    .await;
    wait_runtime(&client, &session, &credential, "waiting_input").await;
    post(
        &client,
        &session,
        &credential,
        &json!({"commandId":"no-boolean","type":"respond",
        "generation":generation,"inputRequestId":"question-1","value":"yes"}),
    )
    .await;
    assert_eq!(
        terminal(&client, &session, &credential, "no-boolean").await["error"]["code"],
        "invalid_answer"
    );
    assert_eq!(
        feedback(&get(&client, &format!("{session}/history"), &credential).await).len(),
        1
    );
    command_succeeds(
        &client,
        &session,
        &credential,
        "confirm-answer",
        json!({"type":"respond",
        "generation":generation,"inputRequestId":"question-1","confirmed":false}),
    )
    .await;
    wait_operation(
        &client,
        &format!("{session}/operations/confirm-prompt"),
        &credential,
        "succeeded",
    )
    .await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "expired-prompt",
        json!({"type":"prompt",
        "generation":generation,"message":"need expired input"}),
    )
    .await;
    post(
        &client,
        &session,
        &credential,
        &json!({"commandId":"expired","type":"respond",
        "generation":generation,"inputRequestId":"question-1","value":"new"}),
    )
    .await;
    assert_eq!(
        terminal(&client, &session, &credential, "expired").await["error"]["code"],
        "input_expired"
    );
    assert_eq!(
        feedback(&get(&client, &format!("{session}/history"), &credential).await).len(),
        2
    );
    command_succeeds(
        &client,
        &session,
        &credential,
        "stop",
        json!({"type":"stop_runtime","generation":generation}),
    )
    .await;
}

#[tokio::test]
async fn feedback_only_history_survives_without_a_model_acknowledgement_or_leaf_change() {
    use sha2::{Digest, Sha256};
    let mut h = Harness::new(
        1,
        vec![
            "--lazy-history".into(),
            "--attention-on-start".into(),
            "--ignore-input-response".into(),
        ],
    )
    .await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let cwd = h.cwd("feedback-only");
    let mut session = create_session(&client, &h, &credential, &cwd).await;
    let id = session.rsplit('/').next().unwrap().to_owned();
    command_succeeds(
        &client,
        &session,
        &credential,
        "start",
        json!({"type":"start_runtime"}),
    )
    .await;
    let waiting = wait_runtime(&client, &session, &credential, "waiting_input").await;
    let before = get(&client, &format!("{session}/history"), &credential).await;
    assert_eq!(before["items"], json!([]));
    assert!(std::fs::read_dir(&cwd).unwrap().all(|entry| {
        entry
            .unwrap()
            .path()
            .extension()
            .is_none_or(|extension| extension != "jsonl")
    }));
    let sequence = h.bus.sequence(Some(&id));
    command_succeeds(
        &client,
        &session,
        &credential,
        "answer",
        json!({"type":"respond",
        "generation":waiting["generation"],"inputRequestId":"question-1","value":"new"}),
    )
    .await;
    let page = get(&client, &format!("{session}/history"), &credential).await;
    assert_eq!(page["items"].as_array().unwrap().len(), 1);
    let item = page["items"][0].clone();
    assert_eq!(item["kind"], "feedback");
    assert_eq!(item["detail"], "Which API?");
    assert_eq!(item["text"], "new");
    let live: Vec<_> = h
        .bus
        .replay(&h.bus.epoch, sequence, Some(&id))
        .unwrap()
        .into_iter()
        .filter_map(|event| {
            if let Event::Update { event, .. } = event
                && let ServerEvent::Timeline { upsert, .. } = &*event
            {
                return Some(
                    upsert
                        .iter()
                        .filter(|entry| entry.kind == "feedback")
                        .map(|entry| serde_json::to_value(entry).unwrap())
                        .collect::<Vec<_>>(),
                );
            }
            None
        })
        .flatten()
        .collect();
    assert_eq!(live, vec![item.clone()]);
    assert_eq!(
        get(&client, &session, &credential).await["hasHistory"],
        true
    );
    assert!(std::fs::read_dir(&cwd).unwrap().all(|entry| {
        entry
            .unwrap()
            .path()
            .extension()
            .is_none_or(|extension| extension != "jsonl")
    }));
    let sync: Value = client
        .post(format!("{session}/history/sync"))
        .bearer_auth(&credential)
        .json(&json!({"anchor":"","oldest":null,"known":[]}))
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(sync["items"], json!([item]));
    assert_eq!(sync["source"]["continues"], true);
    let confirmed: Value = client
        .post(format!("{session}/history/sync"))
        .bearer_auth(&credential)
        .json(
            &json!({"anchor":"","oldest":item["sourceId"],"known":[{"id":"local-feedback",
            "sourceId":item["sourceId"],"textHash":hex::encode(Sha256::digest(b"new"))}]}),
        )
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(confirmed["items"], json!([]));
    assert_eq!(confirmed["confirmed"][0]["id"], "local-feedback");
    assert_eq!(confirmed["confirmed"][0]["item"]["detail"], "Which API?");
    command_succeeds(
        &client,
        &session,
        &credential,
        "stop",
        json!({"type":"stop_runtime","generation":waiting["generation"]}),
    )
    .await;
    h.restart(
        1,
        vec![
            "--lazy-history".into(),
            "--attention-on-start".into(),
            "--ignore-input-response".into(),
        ],
    )
    .await;
    session = format!("{}/api/v4/sessions/{id}", h.url);
    assert_eq!(
        get(&client, &session, &credential).await["hasHistory"],
        true
    );
    assert_eq!(
        get(&client, &format!("{session}/history"), &credential).await["items"],
        json!([item])
    );
    command_succeeds(
        &client,
        &session,
        &credential,
        "resume",
        json!({"type":"start_runtime"}),
    )
    .await;
    let resumed = wait_runtime(&client, &session, &credential, "waiting_input").await;
    assert_eq!(
        get(&client, &format!("{session}/history"), &credential).await["items"],
        json!([item])
    );
    let resumed_sync: Value = client
        .post(format!("{session}/history/sync"))
        .bearer_auth(&credential)
        .json(&json!({"anchor":"","oldest":null,"known":[]}))
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(resumed_sync["items"], json!([item]));
    assert!(std::fs::read_dir(&cwd).unwrap().all(|entry| {
        entry
            .unwrap()
            .path()
            .extension()
            .is_none_or(|extension| extension != "jsonl")
    }));
    command_succeeds(
        &client,
        &session,
        &credential,
        "stop-resumed",
        json!({"type":"stop_runtime","generation":resumed["generation"]}),
    )
    .await;
}

#[tokio::test]
async fn failed_response_transport_creates_no_feedback() {
    let h = Harness::new(1, vec!["--stall-input-on-attention".into()]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let session = create_session(&client, &h, &credential, &h.cwd("feedback-write-failure")).await;
    post(
        &client,
        &session,
        &credential,
        &json!({"commandId":"prompt","type":"prompt","message":"need text"}),
    )
    .await;
    let waiting = wait_runtime(&client, &session, &credential, "waiting_input").await;
    post(&client, &session, &credential, &json!({"commandId":"answer","type":"respond",
        "generation":waiting["generation"],"inputRequestId":"question-1","value":"x".repeat(262144)})).await;
    assert_eq!(
        terminal(&client, &session, &credential, "answer").await["state"],
        "unknown"
    );
    assert!(feedback(&get(&client, &format!("{session}/history"), &credential).await).is_empty());
    tokio::time::timeout(Duration::from_secs(15), async {
        loop {
            if get(&client, &session, &credential).await["runtime"].is_null() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
}

#[tokio::test]
async fn blocked_response_keeps_snapshot_available_and_stop_does_not_wait_for_write_timeout() {
    let h = Harness::new(1, vec!["--stall-input-on-attention".into()]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let session = create_session(&client, &h, &credential, &h.cwd("feedback-blocked-stop")).await;
    let id = session.rsplit('/').next().unwrap();
    post(
        &client,
        &session,
        &credential,
        &json!({"commandId":"prompt","type":"prompt","message":"need text"}),
    )
    .await;
    let waiting = wait_runtime(&client, &session, &credential, "waiting_input").await;
    let sequence = h.bus.sequence(Some(id));
    post(
        &client,
        &session,
        &credential,
        &json!({"commandId":"answer","type":"respond","generation":waiting["generation"],
            "inputRequestId":"question-1","value":"x".repeat(262144)}),
    )
    .await;
    // The fixture never drains stdin after attention. This exceeds pipe capacity, so let the
    // accepted command start writing before testing independent session/lifecycle requests.
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(
        get(
            &client,
            &format!("{session}/operations/answer"),
            &credential
        )
        .await["state"],
        "pending"
    );
    let snapshot =
        tokio::time::timeout(Duration::from_secs(2), get(&client, &session, &credential))
            .await
            .expect("session snapshot blocked behind response transport");
    assert_eq!(snapshot["runtime"]["generation"], waiting["generation"]);
    assert_eq!(
        get(
            &client,
            &format!("{session}/operations/answer"),
            &credential
        )
        .await["state"],
        "pending"
    );
    // Allow the existing three-second termination grace, but not the ten-second write timeout.
    tokio::time::timeout(Duration::from_secs(6), async {
        command_succeeds(
            &client,
            &session,
            &credential,
            "stop",
            json!({"type":"stop_runtime","generation":waiting["generation"]}),
        )
        .await;
        assert!(get(&client, &session, &credential).await["runtime"].is_null());
        assert_eq!(
            terminal(&client, &session, &credential, "answer").await["state"],
            "unknown"
        );
    })
    .await
    .expect("Stop waited for the ten-second response write timeout");
    assert!(feedback(&get(&client, &format!("{session}/history"), &credential).await).is_empty());
    assert!(
        get(&client, &session, &credential).await["timeline"]
            .as_array()
            .unwrap()
            .iter()
            .all(|item| item["kind"] != "feedback")
    );
    assert!(h.bus.replay(&h.bus.epoch, sequence, Some(id)).unwrap().into_iter().all(|event| {
        !matches!(event, Event::Update { event, .. }
            if matches!(&*event, ServerEvent::Timeline { upsert, .. } if upsert.iter().any(|item| item.kind == "feedback")))
    }));
}
