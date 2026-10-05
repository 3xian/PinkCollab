#![cfg(feature = "test-fixtures")]
mod support;
use futures_util::StreamExt;
use serde_json::{Value, json};
use std::time::Duration;
use support::{Harness, wait_operation};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;

#[tokio::test]
async fn interrupt_is_admitted_after_an_accepted_prompt_reaches_omp() {
    let h = Harness::new(1, vec!["--record-prompt-frame".into()]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let cwd = h.cwd("interrupt-order");
    let sessions = format!("{}/api/v4/sessions", h.url);
    let record: Value = client
        .post(&sessions)
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create","hostId":h.host.id,"cwd":cwd}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let session = format!("{sessions}/{}", record["id"].as_str().unwrap());
    let commands = format!("{session}/commands");
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"start","type":"start_runtime"}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let _receipt = wait_operation(
        &client,
        &format!("{session}/operations/start"),
        &credential,
        "succeeded",
    )
    .await;
    let generation = support::runtime_generation(&client, &session, &credential).await;

    assert_eq!(client.post(&commands).bearer_auth(&credential)
        .json(&json!({"commandId":"prompt","type":"prompt","message":"need input before ack","generation":generation}))
        .send().await.unwrap().status(), 202);
    // The prompt's OMP acknowledgement stays pending, but admission must wait only for its write.
    let interrupt = tokio::time::timeout(
        Duration::from_secs(5),
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"interrupt","type":"interrupt","generation":generation}))
            .send(),
    )
    .await
    .unwrap()
    .unwrap();
    assert_eq!(interrupt.status(), 202);
    wait_operation(
        &client,
        &format!("{session}/operations/interrupt"),
        &credential,
        "succeeded",
    )
    .await;
    assert!(std::path::Path::new(&cwd).join("last-prompt.json").exists());
}

#[tokio::test]
async fn response_reaches_omp_while_prompt_ack_is_pending() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let sessions = format!("{}/api/v4/sessions", h.url);
    let record: Value = client
        .post(&sessions)
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create","hostId":h.host.id,"cwd":h.cwd("pending-input")}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let session = format!("{sessions}/{}", record["id"].as_str().unwrap());
    let commands = format!("{session}/commands");
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"prompt","type":"prompt","message":"need input before ack"}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let generation = tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            let detail: Value = client
                .get(&session)
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            if detail["runtime"]["pendingInputs"]
                .as_array()
                .is_some_and(|items| !items.is_empty())
            {
                assert_eq!(detail["runtime"]["workTiming"]["running"], false);
                assert_eq!(detail["runtime"]["workTiming"]["completed"], false);
                assert!(detail["runtime"]["workTiming"]["elapsedMs"].is_u64());
                break detail["runtime"]["generation"].as_str().unwrap().to_owned();
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    assert_eq!(client.post(&commands).bearer_auth(&credential)
        .json(&json!({"commandId":"answer","type":"respond","generation":generation,"inputRequestId":"question-1","value":"new"}))
        .send().await.unwrap().status(), 202);
    let answer = wait_operation(
        &client,
        &format!("{session}/operations/answer"),
        &credential,
        "succeeded",
    )
    .await;
    assert_eq!(answer["state"], "succeeded");
    let prompt = wait_operation(
        &client,
        &format!("{session}/operations/prompt"),
        &credential,
        "succeeded",
    )
    .await;
    assert_eq!(prompt["state"], "succeeded");
    let page: Value = client
        .get(format!("{session}/history"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    let feedback: Vec<_> = page["items"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|item| item["kind"] == "feedback")
        .collect();
    assert_eq!(feedback.len(), 1);
    assert_eq!(feedback[0]["detail"], "Which API?");
    assert_eq!(feedback[0]["text"], "new");
    assert_eq!(feedback[0]["id"], feedback[0]["sourceId"]);
}

#[tokio::test]
async fn stopping_runtime_rejects_new_work_until_exit_is_confirmed() {
    let h = Harness::new(1, vec!["--linger-on-eof".into()]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let sessions = format!("{}/api/v4/sessions", h.url);
    let record: Value = client
        .post(&sessions)
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create","hostId":h.host.id,"cwd":h.cwd("stopping-gate")}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let session = format!("{sessions}/{}", record["id"].as_str().unwrap());
    let commands = format!("{session}/commands");
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"start","type":"start_runtime"}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let _receipt = wait_operation(
        &client,
        &format!("{session}/operations/start"),
        &credential,
        "succeeded",
    )
    .await;
    let generation = support::runtime_generation(&client, &session, &credential).await;
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"stop","type":"stop_runtime","generation":generation}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let detail: Value = client
                .get(&session)
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            if detail["runtime"]["state"] == "stopping" {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    assert_eq!(client.post(&commands).bearer_auth(&credential)
        .json(&json!({"commandId":"model","type":"select_model","generation":generation,"provider":"fixture","modelId":"fast"}))
        .send().await.unwrap().status(), 202);
    let failure = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let receipt: Value = client
                .get(format!("{session}/operations/model"))
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            if receipt["state"] == "failed" {
                break receipt;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    assert_eq!(failure["error"]["code"], "runtime_stopping");
    wait_operation(
        &client,
        &format!("{session}/operations/stop"),
        &credential,
        "succeeded",
    )
    .await;
}

#[tokio::test]
async fn protocol_session_list_pages_by_stable_creation_order() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let endpoint = format!("{}/api/v4/sessions", h.url);
    for number in 0..3 {
        let response = client
            .post(&endpoint)
            .bearer_auth(&credential)
            .json(&json!({"commandId":format!("create-{number}"),"hostId":h.host.id,"cwd":h.cwd(&format!("page-{number}"))}))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 201);
    }
    let mut seen = std::collections::HashSet::new();
    let mut cursor: Option<String> = None;
    loop {
        let mut request = client
            .get(&endpoint)
            .bearer_auth(&credential)
            .query(&[("limit", "1")]);
        if let Some(value) = cursor.as_deref() {
            request = request.query(&[("cursor", value)]);
        }
        let page: Value = request.send().await.unwrap().json().await.unwrap();
        let sessions = page["sessions"].as_array().unwrap();
        assert_eq!(sessions.len(), 1);
        assert!(seen.insert(sessions[0]["session"]["id"].as_str().unwrap().to_owned()));
        cursor = page["nextCursor"].as_str().map(str::to_owned);
        if cursor.is_none() {
            break;
        }
    }
    assert_eq!(seen.len(), 3);
    let invalid = client
        .get(&endpoint)
        .bearer_auth(&credential)
        .query(&[("cursor", "bad")])
        .send()
        .await
        .unwrap();
    assert_eq!(invalid.status(), 400);
    assert_eq!(
        invalid.json::<Value>().await.unwrap()["code"],
        "invalid_cursor"
    );
}

#[tokio::test]
async fn protocol_lazy_session_prompt_receipt_and_generation_bound_stop() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let cwd = h.cwd("v2-task");
    let create = json!({"commandId":"create-one","hostId":h.host.id,"cwd":cwd});
    let endpoint = format!("{}/api/v4/sessions", h.url);
    let response = client
        .post(&endpoint)
        .bearer_auth(&credential)
        .json(&create)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 201);
    let session: Value = response.json().await.unwrap();
    let id = session["id"].as_str().unwrap();
    let detail: Value = client
        .get(format!("{endpoint}/{id}"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(
        detail["runtime"].is_null(),
        "creating a session must not start OMP"
    );
    assert!(detail["session"].get("status").is_none());
    assert_eq!(
        client
            .post(&endpoint)
            .bearer_auth(&credential)
            .json(&create)
            .send()
            .await
            .unwrap()
            .status(),
        200
    );
    let changed = json!({"commandId":"create-one","hostId":h.host.id,"cwd":h.cwd("other")});
    let conflict = client
        .post(&endpoint)
        .bearer_auth(&credential)
        .json(&changed)
        .send()
        .await
        .unwrap();
    assert_eq!(conflict.status(), 409);
    assert_eq!(
        conflict.json::<Value>().await.unwrap()["code"],
        "idempotency_conflict"
    );

    let commands = format!("{endpoint}/{id}/commands");
    let prompt = json!({"commandId":"prompt-one","type":"prompt","message":"hello"});
    let accepted = client
        .post(&commands)
        .bearer_auth(&credential)
        .json(&prompt)
        .send()
        .await
        .unwrap();
    assert_eq!(accepted.status(), 202, "{}", accepted.text().await.unwrap());
    let operation_url = format!("{endpoint}/{id}/operations/prompt-one");
    let _operation = tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            let receipt: Value = client
                .get(&operation_url)
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            if receipt["state"] == "succeeded" {
                break receipt;
            }
            if receipt["state"] == "failed" || receipt["state"] == "unknown" {
                panic!("unexpected receipt: {receipt}");
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    let generation =
        support::runtime_generation(&client, &format!("{endpoint}/{id}"), &credential).await;
    assert!(!generation.is_empty());
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&prompt)
            .send()
            .await
            .unwrap()
            .status(),
        200
    );
    let view: Value = client
        .get(format!("{endpoint}/{id}"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(view["runtime"]["generation"], generation);
    assert_eq!(view["runtime"]["state"], "idle");
    assert_eq!(view["runtime"]["workTiming"]["completed"], true);
    assert_eq!(view["runtime"]["workTiming"]["running"], false);
    assert!(view["runtime"]["workTiming"]["elapsedMs"].is_u64());
    let tool = view["timeline"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["kind"] == "tool")
        .expect("live tool result");
    assert_eq!(tool["tool"]["name"], "bash");
    assert!(tool["tool"]["arguments"].is_null());
    assert!(tool["tool"]["result"].is_null());
    assert_eq!(tool["tool"]["summary"]["target"], "cargo test");
    let details: Value = client
        .get(format!(
            "{endpoint}/{id}/tools/{}",
            tool["tool"]["callId"].as_str().unwrap()
        ))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(details["text"].as_str().unwrap().contains("tests passed"));
    let early_settled = json!({"commandId":"prompt-settled-first","type":"prompt","message":"settle before ack","generation":generation});
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&early_settled)
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let settled_url = format!("{endpoint}/{id}/operations/prompt-settled-first");
    tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            let receipt: Value = client
                .get(&settled_url)
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            if receipt["state"] == "succeeded" {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    // The fixture intentionally sends the acknowledgement after its terminal event.
    tokio::time::sleep(Duration::from_millis(400)).await;
    let settled_view: Value = client
        .get(format!("{endpoint}/{id}"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(settled_view["runtime"]["state"], "idle");
    let stop = json!({"commandId":"stop-one","type":"stop_runtime","generation":generation});
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&stop)
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            let receipt: Value = client
                .get(format!("{endpoint}/{id}/operations/stop-one"))
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            if receipt["state"] == "succeeded" {
                break;
            }
            if receipt["state"] == "failed" || receipt["state"] == "unknown" {
                panic!("unexpected stop receipt: {receipt}");
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    let view: Value = client
        .get(format!("{endpoint}/{id}"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(view["runtime"].is_null());
}

#[tokio::test]
async fn protocol_websocket_snapshot_precedes_ordered_events() {
    use futures_util::SinkExt;
    async fn next_json<S>(socket: &mut S) -> Value
    where
        S: futures_util::Stream<
                Item = Result<
                    tokio_tungstenite::tungstenite::Message,
                    tokio_tungstenite::tungstenite::Error,
                >,
            > + Unpin,
    {
        loop {
            if let Some(Ok(tokio_tungstenite::tungstenite::Message::Text(text))) =
                socket.next().await
            {
                return serde_json::from_str(&text).unwrap();
            }
        }
    }
    let h = Harness::new(1, vec![]).await;
    let credential = h.pair().await;
    let mut request = format!("{}/api/v4/events", h.url.replace("http://", "ws://"))
        .into_client_request()
        .unwrap();
    request.headers_mut().insert(
        "Authorization",
        format!("Bearer {credential}").parse().unwrap(),
    );
    let (mut socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
    let first = next_json(&mut socket).await;
    assert_eq!(first["type"], "host_snapshot");
    assert_eq!(first["protocolVersion"], 4);
    let client = reqwest::Client::new();
    let response: Value = client
        .post(format!("{}/api/v4/sessions", h.url))
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create-ws","hostId":h.host.id,"cwd":h.cwd("ws")}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let id = response["id"].as_str().unwrap();
    let change = next_json(&mut socket).await;
    assert_eq!(change["type"], "session_upsert");
    assert_eq!(change["session"]["id"], id);
    socket
        .send(tokio_tungstenite::tungstenite::Message::Text(
            json!({"type":"subscribe","sessionId":id})
                .to_string()
                .into(),
        ))
        .await
        .unwrap();
    let detail = next_json(&mut socket).await;
    assert_eq!(detail["type"], "session_snapshot");
    assert_eq!(detail["session"]["id"], id);
    assert!(detail["runtime"].is_null());
    assert_eq!(
        client
            .post(format!("{}/api/v4/sessions/{id}/commands", h.url))
            .bearer_auth(&credential)
            .json(&json!({"commandId":"prompt-ws","type":"prompt","message":"hello"}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let patch = tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            let frame = next_json(&mut socket).await;
            if frame["type"] == "timeline"
                && frame["upsert"]
                    .as_array()
                    .is_some_and(|items| !items.is_empty())
            {
                break frame;
            }
        }
    })
    .await
    .expect("live timeline patch");
    assert!(
        patch["upsert"]
            .as_array()
            .is_some_and(|items| !items.is_empty())
    );
}

#[tokio::test]
async fn protocol_resume_keeps_transcript_and_uses_new_generation() {
    let h = Harness::new(1, vec![]).await;
    let credential = h.pair().await;
    let client = reqwest::Client::new();
    let create: Value = client
        .post(format!("{}/api/v4/sessions", h.url))
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create-resume","hostId":h.host.id,"cwd":h.cwd("resume")}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let id = create["id"].as_str().unwrap();
    let session_url = format!("{}/api/v4/sessions/{id}", h.url);
    let commands = format!("{session_url}/commands");
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"start","type":"start_runtime"}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let _receipt = wait_operation(
        &client,
        &format!("{session_url}/operations/start"),
        &credential,
        "succeeded",
    )
    .await;
    let old_generation = support::runtime_generation(&client, &session_url, &credential).await;
    let catalog: Value = client
        .get(format!("{session_url}/models"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(catalog["models"].as_array().unwrap().len(), 2);
    assert_eq!(
        catalog["models"][0]["thinkingLevels"],
        json!(["off", "low", "high"])
    );
    assert_eq!(
        catalog["models"][1]["thinkingLevels"],
        json!(["off", "medium", "high"])
    );
    assert!(
        catalog["thinkingLevels"]
            .as_array()
            .unwrap()
            .contains(&json!("high"))
    );
    for (command_id, command) in [
        (
            "model",
            json!({"type":"select_model","generation":old_generation,"provider":"fixture","modelId":"smart"}),
        ),
        (
            "thinking",
            json!({"type":"set_thinking_level","generation":old_generation,"level":"high"}),
        ),
    ] {
        let mut request = command;
        request["commandId"] = json!(command_id);
        assert_eq!(
            client
                .post(&commands)
                .bearer_auth(&credential)
                .json(&request)
                .send()
                .await
                .unwrap()
                .status(),
            202
        );
        wait_operation(
            &client,
            &format!("{session_url}/operations/{command_id}"),
            &credential,
            "succeeded",
        )
        .await;
    }
    let first = client
        .post(&commands)
        .bearer_auth(&credential)
        .json(&json!({"commandId":"first","type":"prompt","message":"hello","generation":old_generation}))
        .send()
        .await
        .unwrap();
    assert_eq!(first.status(), 202);
    wait_operation(
        &client,
        &format!("{session_url}/operations/first"),
        &credential,
        "succeeded",
    )
    .await;
    let detail: Value = client
        .get(&session_url)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(detail["runtime"]["model"]["id"], "smart");
    assert_eq!(detail["runtime"]["model"]["thinkingLevel"], "high");
    let stop = json!({"commandId":"stop-old","type":"stop_runtime","generation":old_generation});
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&stop)
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    wait_operation(
        &client,
        &format!("{session_url}/operations/stop-old"),
        &credential,
        "succeeded",
    )
    .await;
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"late-prompt","type":"prompt","message":"must not restart","generation":old_generation}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let rejected = wait_operation(
        &client,
        &format!("{session_url}/operations/late-prompt"),
        &credential,
        "failed",
    )
    .await;
    assert_eq!(rejected["error"]["code"], "runtime_required");
    let after_stop: Value = client
        .get(&session_url)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(after_stop["runtime"].is_null());
    let page: Value = client
        .get(format!("{session_url}/history?limit=1"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(page["items"].as_array().unwrap().len(), 1);
    let old_cursor = page["nextCursor"].as_str().unwrap();
    let earlier: Value = client
        .get(format!("{session_url}/history?cursor={old_cursor}"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(earlier["source"]["id"], page["source"]["id"]);
    assert_eq!(earlier["items"][0]["kind"], "user");
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(&json!({"commandId":"resume","type":"start_runtime"}))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let _receipt = wait_operation(
        &client,
        &format!("{session_url}/operations/resume"),
        &credential,
        "succeeded",
    )
    .await;
    let new_generation = support::runtime_generation(&client, &session_url, &credential).await;
    assert_ne!(old_generation, new_generation);
    let stale_stop = client
        .post(&commands)
        .bearer_auth(&credential)
        .json(&json!({"commandId":"stale-stop","type":"stop_runtime","generation":old_generation}))
        .send()
        .await
        .unwrap();
    assert_eq!(stale_stop.status(), 202);
    let stale_receipt = wait_operation(
        &client,
        &format!("{session_url}/operations/stale-stop"),
        &credential,
        "failed",
    )
    .await;
    assert_eq!(stale_receipt["error"]["code"], "generation_mismatch");
    assert_eq!(
        client
            .post(&commands)
            .bearer_auth(&credential)
            .json(
                &json!({"commandId":"second","type":"prompt","message":"again","generation":new_generation})
            )
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    wait_operation(
        &client,
        &format!("{session_url}/operations/second"),
        &credential,
        "succeeded",
    )
    .await;
    let stale = client
        .get(format!("{session_url}/history?cursor={old_cursor}"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap();
    assert_eq!(stale.status(), 200);
    assert!(
        !stale.json::<Value>().await.unwrap()["items"]
            .as_array()
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn v1_routes_explain_the_breaking_upgrade() {
    let h = Harness::new(1, vec![]).await;
    let response = reqwest::Client::new()
        .get(format!("{}/api/v1/host", h.url))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 426);
    assert_eq!(
        response.json::<Value>().await.unwrap()["code"],
        "protocol_upgrade_required"
    );
}
