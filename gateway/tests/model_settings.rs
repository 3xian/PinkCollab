#![cfg(feature = "test-fixtures")]
mod support;

use serde_json::{Value, json};
use support::{Harness, command_succeeds, create_session, runtime_generation};

#[tokio::test]
async fn resumed_conversation_uses_current_omp_default_instead_of_historical_model() {
    let h = Harness::new(
        1,
        vec![
            "--restore-smart-model".into(),
            "--record-prompt-frame".into(),
        ],
    )
    .await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let cwd = h.cwd("resume-default-model");
    let session = create_session(&client, &h, &credential, &cwd).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "start",
        json!({"type":"start_runtime"}),
    )
    .await;
    let generation = runtime_generation(&client, &session, &credential).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "stop",
        json!({"type":"stop_runtime","generation":generation}),
    )
    .await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "resume",
        json!({"type":"prompt","message":"continue"}),
    )
    .await;

    let snapshot: Value = client
        .get(&session)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(snapshot["runtime"]["model"]["id"], "fast");
    let prompt: Value = serde_json::from_str(
        &std::fs::read_to_string(std::path::Path::new(&cwd).join("last-prompt.json")).unwrap(),
    )
    .unwrap();
    assert_eq!(prompt["fixtureModelId"], "fast");
}

#[tokio::test]
async fn fast_mode_commands_update_runtime_snapshot_in_both_directions() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let session = create_session(&client, &h, &credential, &h.cwd("fast-mode")).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "start",
        json!({"type":"start_runtime"}),
    )
    .await;
    let generation = runtime_generation(&client, &session, &credential).await;
    for (command_id, enabled) in [("fast-on", true), ("fast-off", false)] {
        command_succeeds(
            &client,
            &session,
            &credential,
            command_id,
            json!({"type":"set_fast_mode","generation":generation,"enabled":enabled}),
        )
        .await;
        let snapshot: Value = client
            .get(&session)
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        assert_eq!(snapshot["runtime"]["model"]["fastModeEnabled"], enabled);
        assert_eq!(snapshot["runtime"]["model"]["fastModeActive"], enabled);
    }
}

#[tokio::test]
async fn rpc_v2_large_catalog_preserves_model_selection_and_thinking_state() {
    let h = Harness::new(1, vec!["--rpc-v2".into(), "--large-models".into()]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let session = create_session(&client, &h, &credential, &h.cwd("large-model-catalog")).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "start",
        json!({"type":"start_runtime"}),
    )
    .await;
    let generation = runtime_generation(&client, &session, &credential).await;
    let catalog: Value = client
        .get(format!("{session}/models"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    let models = catalog["models"].as_array().unwrap();
    assert_eq!(models.len(), 642);
    assert_eq!(models[0]["id"], "fast");
    assert_eq!(models[1]["id"], "smart");
    assert_eq!(catalog["thinkingLevels"], json!(["off", "low", "high"]));
    for (index, model) in models[2..].iter().enumerate() {
        assert_eq!(model["provider"], "fixture");
        assert_eq!(model["id"], format!("large-{index}"));
        assert_eq!(model["name"], format!("Fixture Large {index}"));
        assert_eq!(model["thinkingLevel"], Value::Null);
        assert_eq!(
            model["thinkingLevels"],
            json!(["off", "low", "medium", "high"])
        );
        assert!(model.get("fixtureMetadata").is_none());
        assert!(model.get("contextWindow").is_none());
    }

    // Select the tail of the catalog to detect truncated or reordered reassembly.
    command_succeeds(
        &client,
        &session,
        &credential,
        "select-large",
        json!({"type":"select_model","generation":generation,"provider":"fixture","modelId":"large-639"}),
    )
    .await;
    for (command_id, level) in [("thinking-high", "high"), ("thinking-off", "off")] {
        command_succeeds(
            &client,
            &session,
            &credential,
            command_id,
            json!({"type":"set_thinking_level","generation":generation,"level":level}),
        )
        .await;
        let snapshot: Value = client
            .get(&session)
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .error_for_status()
            .unwrap()
            .json()
            .await
            .unwrap();
        assert_eq!(snapshot["runtime"]["generation"], generation);
        assert_eq!(snapshot["runtime"]["model"]["provider"], "fixture");
        assert_eq!(snapshot["runtime"]["model"]["id"], "large-639");
        assert_eq!(snapshot["runtime"]["model"]["name"], "Fixture Large 639");
        assert_eq!(snapshot["runtime"]["model"]["thinkingLevel"], level);
        assert_eq!(
            snapshot["runtime"]["model"]["thinkingLevels"],
            json!(["off", "low", "medium", "high"])
        );
        assert_eq!(snapshot["runtime"]["model"]["fastModeEnabled"], false);
        assert_eq!(snapshot["runtime"]["model"]["fastModeActive"], false);
    }
    let refreshed_catalog: Value = client
        .get(format!("{session}/models"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(refreshed_catalog["models"], catalog["models"]);
    assert_eq!(
        refreshed_catalog["thinkingLevels"],
        json!(["off", "low", "medium", "high"])
    );
}

#[tokio::test]
async fn startup_event_burst_preserves_state_and_catalog_across_reattach() {
    for rpc_v2 in [false, true] {
        let mut args = vec![
            "--startup-event-burst".into(),
            "--restore-smart-model".into(),
        ];
        if rpc_v2 {
            args.push("--rpc-v2".into());
        }
        let h = Harness::new(1, args).await;
        let client = reqwest::Client::new();
        let credential = h.pair().await;
        let session = create_session(&client, &h, &credential, &h.cwd("startup-burst")).await;
        let mut previous: Option<(Value, Value)> = None;
        for cycle in 0..2 {
            // Three events before each get_state response exhaust the one-slot
            // output queue if startup holds the consumer's feedback_order gate.
            command_succeeds(
                &client,
                &session,
                &credential,
                &format!("start-{cycle}"),
                json!({"type":"start_runtime"}),
            )
            .await;
            let started: Value = client
                .get(&session)
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .error_for_status()
                .unwrap()
                .json()
                .await
                .unwrap();
            let generation = started["runtime"]["generation"].as_str().unwrap();
            assert_eq!(started["runtime"]["state"], "idle");
            assert_eq!(started["runtime"]["model"]["provider"], "fixture");
            assert_eq!(started["runtime"]["model"]["id"], "fast");
            assert_eq!(started["runtime"]["model"]["name"], "Fixture Fast");
            assert_eq!(started["runtime"]["model"]["thinkingLevel"], "medium");
            assert_eq!(started["runtime"]["model"]["fastModeEnabled"], false);
            assert_eq!(started["runtime"]["model"]["fastModeActive"], false);
            assert_eq!(started["runtime"]["pendingInputs"], json!([]));
            assert_eq!(started["timeline"], json!([]));
            assert_eq!(started["hasHistory"], true);
            let catalog: Value = client
                .get(format!("{session}/models"))
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .error_for_status()
                .unwrap()
                .json()
                .await
                .unwrap();
            assert_eq!(catalog["models"].as_array().unwrap().len(), 2);
            assert_eq!(catalog["models"][0]["id"], "fast");
            assert_eq!(catalog["models"][1]["id"], "smart");
            assert_eq!(catalog["thinkingLevels"], json!(["off", "low", "high"]));
            if let Some((previous_state, previous_catalog)) = &previous {
                assert_ne!(
                    started["runtime"]["generation"],
                    previous_state["runtime"]["generation"]
                );
                assert_eq!(
                    started["runtime"]["model"],
                    previous_state["runtime"]["model"]
                );
                assert_eq!(started["session"], previous_state["session"]);
                assert_eq!(&catalog, previous_catalog);
            }
            command_succeeds(
                &client,
                &session,
                &credential,
                &format!("already-started-{cycle}"),
                json!({"type":"start_runtime"}),
            )
            .await;
            let refreshed: Value = client
                .get(&session)
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .error_for_status()
                .unwrap()
                .json()
                .await
                .unwrap();
            assert_eq!(refreshed["runtime"]["generation"], generation);
            assert_eq!(refreshed["runtime"]["state"], "idle");
            assert_eq!(refreshed["runtime"]["model"], started["runtime"]["model"]);
            assert_eq!(refreshed["session"], started["session"]);
            assert_eq!(refreshed["timeline"], json!([]));
            command_succeeds(
                &client,
                &session,
                &credential,
                &format!("stop-{cycle}"),
                json!({"type":"stop_runtime","generation":generation}),
            )
            .await;
            let stopped: Value = client
                .get(&session)
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .error_for_status()
                .unwrap()
                .json()
                .await
                .unwrap();
            assert!(stopped["runtime"].is_null());
            assert_eq!(stopped["session"], started["session"]);
            assert_eq!(stopped["hasHistory"], true);
            // The second start switches to the saved session, whose historical
            // smart model must still be reset to OMP's current fast default.
            previous = Some((started, catalog));
        }
    }
}

#[tokio::test]
async fn slash_catalog_is_authenticated_runtime_scoped_and_preserves_metadata() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let session = create_session(&client, &h, &credential, &h.cwd("commands")).await;
    assert_eq!(
        client
            .get(format!("{session}/commands"))
            .send()
            .await
            .unwrap()
            .status(),
        401
    );
    let stopped: Value = client
        .get(format!("{session}/commands"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(stopped["code"], "runtime_required");
    command_succeeds(
        &client,
        &session,
        &credential,
        "start",
        json!({"type":"start_runtime"}),
    )
    .await;
    let catalog: Value = client
        .get(format!("{session}/commands"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(
        catalog["commands"][0],
        json!({
            "name":"model","source":"builtin","aliases":["models"],"description":"Select model",
            "input":{"hint":"[model]"}
        })
    );
    assert_eq!(
        catalog["commands"][1]["subcommands"][1],
        json!({"name":"status","usage":"[verbose]"})
    );
    assert_eq!(catalog["commands"][2]["source"], "extension");
    assert_eq!(catalog["commands"].as_array().unwrap().len(), 4);
    assert_eq!(catalog["commands"][2]["name"], "fixture:review");
    assert_eq!(catalog["commands"][3]["name"], "session");
    assert_eq!(
        catalog["commands"][3]["subcommands"],
        json!([{"name":"info"},{"name":"pin"}])
    );
    assert!(catalog["commands"][3].get("input").is_none());
    let generation = runtime_generation(&client, &session, &credential).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "stop",
        json!({"type":"stop_runtime","generation":generation}),
    )
    .await;
    assert_eq!(
        client
            .get(format!("{session}/commands"))
            .bearer_auth(&credential)
            .send()
            .await
            .unwrap()
            .status(),
        409
    );
}

#[tokio::test]
async fn local_slash_outputs_are_ordered_durable_and_complete_without_agent_end() {
    let mut h = Harness::new(
        1,
        vec!["--lazy-history".into(), "--record-prompt-frame".into()],
    )
    .await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let cwd = h.cwd("local-commands");
    let session = create_session(&client, &h, &credential, &cwd).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "start",
        json!({"type":"start_runtime"}),
    )
    .await;
    let generation = runtime_generation(&client, &session, &credential).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "local",
        json!({"type":"prompt","generation":generation,"message":"/fixture multi"}),
    )
    .await;
    let recorded: Value = serde_json::from_str(
        &std::fs::read_to_string(std::path::Path::new(&cwd).join("last-prompt.json")).unwrap(),
    )
    .unwrap();
    assert_eq!(recorded["message"], "/fixture multi");
    let id = session.rsplit('/').next().unwrap();
    let receipt = h
        .store
        .recent_operations(id, 20)
        .unwrap()
        .into_iter()
        .find(|operation| operation.command_id == "local")
        .unwrap();
    assert_eq!(receipt.result.unwrap()["agentInvoked"], false);
    let outputs = wait_command_outputs(&client, &session, &credential, 2).await;
    assert_eq!(outputs[0]["text"], "Current model: fixture/smart");
    assert_eq!(outputs[1]["text"], "Second command output");
    assert!(
        outputs
            .iter()
            .all(|item| item["kind"] == "system" && item["detail"] == "command output")
    );
    command_succeeds(
        &client,
        &session,
        &credential,
        "stop",
        json!({"type":"stop_runtime","generation":generation}),
    )
    .await;
    h.restart(1, vec!["--lazy-history".into()]).await;
    let session = format!("{}/api/v4/sessions/{id}", h.url);
    assert_eq!(
        wait_command_outputs(&client, &session, &credential, 2).await,
        outputs
    );
    // Reattaching a lazy OMP session moves durable root outputs with the mapping.
    command_succeeds(
        &client,
        &session,
        &credential,
        "restart",
        json!({"type":"start_runtime"}),
    )
    .await;
    assert_eq!(
        wait_command_outputs(&client, &session, &credential, 2).await,
        outputs
    );
}

#[tokio::test]
async fn rejected_local_timeline_insert_fails_generation_without_volatile_output() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let session = create_session(&client, &h, &credential, &h.cwd("rejected-command-output")).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "start",
        json!({"type":"start_runtime"}),
    )
    .await;
    let generation = runtime_generation(&client, &session, &credential).await;
    let db = rusqlite::Connection::open(h.dir.path().join("data/pinkcollab.db")).unwrap();
    db.execute_batch(
        "CREATE TRIGGER reject_local_timeline BEFORE INSERT ON local_timeline_records
         BEGIN SELECT RAISE(FAIL, 'injected local timeline failure'); END;",
    )
    .unwrap();
    let mut events = h.bus.subscribe();
    assert_eq!(
        client
            .post(format!("{session}/commands"))
            .bearer_auth(&credential)
            .json(&json!({
                "commandId":"local-failure", "type":"prompt",
                "generation":generation, "message":"/fixture multi"
            }))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let receipt = support::wait_operation(
        &client,
        &format!("{session}/operations/local-failure"),
        &credential,
        "unknown",
    )
    .await;
    assert_eq!(receipt["error"]["code"], "output_persistence_failed");
    let stored = h
        .store
        .recent_operations(session.rsplit('/').next().unwrap(), 20)
        .unwrap()
        .into_iter()
        .find(|receipt| receipt.command_id == "local-failure")
        .unwrap();
    assert!(
        stored.error.unwrap()["message"]
            .as_str()
            .unwrap()
            .contains("injected local timeline failure")
    );
    // Shutdown must finish even though the capacity-one output queue was full.
    tokio::time::timeout(std::time::Duration::from_secs(8), async {
        loop {
            let snapshot: Value = client
                .get(&session)
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .error_for_status()
                .unwrap()
                .json()
                .await
                .unwrap();
            assert!(
                snapshot["timeline"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .all(|item| item["detail"] != "command output")
            );
            if snapshot["runtime"].is_null() {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    while let Ok(event) = events.try_recv() {
        if let pinkcollab_gateway::events::Event::Update { event, .. } = event {
            match event.as_ref() {
                pinkcollab_gateway::protocol::ServerEvent::Timeline { upsert, .. } => {
                    assert!(upsert.iter().all(|item| item.detail != "command output"));
                }
                pinkcollab_gateway::protocol::ServerEvent::Operation { operation, .. }
                    if operation.id == "local-failure" =>
                {
                    assert_ne!(operation.state, "succeeded");
                }
                _ => {}
            }
        }
    }
    let history: Value = client
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
    assert_eq!(history["items"], json!([]));
    let receipt: Value = client
        .get(format!("{session}/operations/local-failure"))
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(receipt["state"], "unknown");
    assert_eq!(receipt["error"]["code"], "output_persistence_failed");
    db.execute_batch("DROP TRIGGER reject_local_timeline;")
        .unwrap();
    // Failure belongs to the exited generation, not a future healthy process.
    command_succeeds(
        &client,
        &session,
        &credential,
        "restart",
        json!({"type":"start_runtime"}),
    )
    .await;
    let generation = runtime_generation(&client, &session, &credential).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "local-recovered",
        json!({"type":"prompt","generation":generation,"message":"/fixture multi"}),
    )
    .await;
    let recovered = wait_command_outputs(&client, &session, &credential, 2).await;
    assert_eq!(recovered[0]["text"], "Current model: fixture/smart");
    assert_eq!(recovered[1]["text"], "Second command output");
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
async fn slash_settings_failures_and_session_changes_reach_consumers() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let session = create_session(&client, &h, &credential, &h.cwd("slash-settings")).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "start",
        json!({"type":"start_runtime"}),
    )
    .await;
    let generation = runtime_generation(&client, &session, &credential).await;
    command_succeeds(
        &client,
        &session,
        &credential,
        "fast",
        json!({"type":"prompt","generation":generation,"message":"/fast on"}),
    )
    .await;
    let snapshot: Value = client
        .get(&session)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(snapshot["runtime"]["state"], "idle");
    assert_eq!(snapshot["runtime"]["model"]["id"], "smart");
    assert_eq!(snapshot["runtime"]["model"]["thinkingLevel"], "high");
    assert_eq!(snapshot["runtime"]["model"]["fastModeEnabled"], true);
    command_succeeds(
        &client,
        &session,
        &credential,
        "rename",
        json!({"type":"prompt","generation":generation,"message":"/rename Slash title"}),
    )
    .await;
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        loop {
            let snapshot: Value = client
                .get(&session)
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            if snapshot["session"]["title"] == "Slash title" {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    for (id, message, code) in [
        ("error", "/fixture error", "omp_rejected"),
        ("delete", "/session:delete", "unsupported_capability"),
        ("move", "/worktree feature", "unsupported_capability"),
    ] {
        assert_eq!(client.post(format!("{session}/commands")).bearer_auth(&credential)
            .json(&json!({"commandId":id,"type":"prompt","generation":generation,"message":message}))
            .send().await.unwrap().status(), 202);
        let receipt = support::wait_operation(
            &client,
            &format!("{session}/operations/{id}"),
            &credential,
            "failed",
        )
        .await;
        assert_eq!(receipt["error"]["code"], code);
    }
    assert_eq!(
        client
            .post(format!("{session}/commands"))
            .bearer_auth(&credential)
            .json(
                &json!({"commandId":"switch","type":"prompt","generation":generation,
                "message":"/fixture switch"})
            )
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    // Exit and refresh can each settle this receipt; either must leave the outcome
    // unknown, never claim success or adopt the unexpected transcript.
    support::wait_operation(
        &client,
        &format!("{session}/operations/switch"),
        &credential,
        "unknown",
    )
    .await;
    let snapshot: Value = client
        .get(&session)
        .bearer_auth(&credential)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(snapshot["runtime"].is_null());
    let outputs = wait_command_outputs(&client, &session, &credential, 3).await;
    assert!(
        outputs
            .iter()
            .any(|item| item["text"] == "Could not run fixture command")
    );
    let id = session.rsplit('/').next().unwrap();
    assert!(
        !h.store
            .v2_session(id)
            .unwrap()
            .unwrap()
            .engine_session_ref
            .unwrap()
            .ends_with("changed.jsonl")
    );
}

#[tokio::test]
async fn slash_discovery_rejection_and_runtime_exit_never_return_stale_commands() {
    for flag in ["--unsupported-commands", "--slow-commands"] {
        let h = Harness::new(1, vec![flag.into()]).await;
        let client = reqwest::Client::new();
        let credential = h.pair().await;
        let cwd = h.cwd("command-errors");
        let session = create_session(&client, &h, &credential, &cwd).await;
        command_succeeds(
            &client,
            &session,
            &credential,
            "start",
            json!({"type":"start_runtime"}),
        )
        .await;
        let request = client
            .get(format!("{session}/commands"))
            .bearer_auth(&credential)
            .send();
        if flag == "--slow-commands" {
            let request = tokio::spawn(request);
            tokio::time::timeout(std::time::Duration::from_secs(5), async {
                while !std::path::Path::new(&cwd)
                    .join("catalog-requested")
                    .exists()
                {
                    tokio::time::sleep(std::time::Duration::from_millis(10)).await;
                }
            })
            .await
            .unwrap();
            let generation = runtime_generation(&client, &session, &credential).await;
            command_succeeds(
                &client,
                &session,
                &credential,
                "stop",
                json!({"type":"stop_runtime","generation":generation}),
            )
            .await;
            assert_eq!(request.await.unwrap().unwrap().status(), 409);
        } else {
            let response = request.await.unwrap();
            assert_eq!(response.status(), 409);
            let body: Value = response.json().await.unwrap();
            assert_eq!(body["code"], "unsupported_capability");
        }
    }
}

async fn wait_command_outputs(
    client: &reqwest::Client,
    session: &str,
    credential: &str,
    count: usize,
) -> Vec<Value> {
    tokio::time::timeout(std::time::Duration::from_secs(8), async {
        loop {
            let page: Value = client
                .get(format!("{session}/history"))
                .bearer_auth(credential)
                .send()
                .await
                .unwrap()
                .error_for_status()
                .unwrap()
                .json()
                .await
                .unwrap();
            let outputs: Vec<_> = page["items"]
                .as_array()
                .unwrap()
                .iter()
                .filter(|item| item["kind"] == "system")
                .cloned()
                .collect();
            if outputs.len() == count {
                return outputs;
            }
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap()
}
