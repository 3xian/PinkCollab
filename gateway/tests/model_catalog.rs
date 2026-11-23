#![cfg(feature = "test-fixtures")]
mod support;

use pinkcollab_gateway::workspace;
use serde_json::{Value, json};
use std::{fs, path::Path};
use support::{Harness, create_session};

async fn get(client: &reqwest::Client, url: &str, token: &str) -> Value {
    client
        .get(url)
        .bearer_auth(token)
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap()
}

#[tokio::test]
async fn browse_held_host_session_without_adopting_or_starting_and_resolve_each_workspace() {
    let files = tempfile::tempdir().unwrap();
    let h = Harness::new(
        1,
        vec!["--session-dir".into(), workspace::display(files.path())],
    )
    .await;
    let cwd = h.cwd("host-project");
    let cwd = Path::new(&cwd);
    let transcript = files.path().join("history.jsonl");
    let header = json!({"type":"session","version":3,"id":"host-owned","cwd":cwd,"timestamp":"2026-01-01T00:00:00Z"});
    let original = format!("{header}\n");
    fs::write(&transcript, &original).unwrap();
    fs::write(cwd.join("held-session"), "host-owned").unwrap();
    fs::write(
        cwd.join("fixture-model-catalog.json"),
        json!({"models":[
            {"provider":"host","id":"custom","name":"Project model","kind":"chat","reasoning":true,"thinking":["medium","high"],"contextWindow":40000},
            {"provider":"host","id":"simple","name":"No reasoning","kind":"chat","reasoning":false,"thinking":null}
        ]})
        .to_string(),
    )
    .unwrap();
    let client = reqwest::Client::new();
    let token = h.pair().await;
    let sessions = format!("{}/api/v4/sessions", h.url);
    let list = get(&client, &sessions, &token).await;
    let id = list["sessions"][0]["session"]["id"].as_str().unwrap();
    let session = format!("{sessions}/{id}");
    let url = format!("{session}/model-catalog");
    assert_eq!(client.get(&url).send().await.unwrap().status(), 401);
    let missing = client
        .get(format!("{sessions}/missing/model-catalog"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(missing.status(), 404);
    assert_eq!(
        get(&client, &url, &token).await,
        json!({"models":[
            {"provider":"host","id":"custom","name":"Project model","thinkingLevels":["off","medium","high"]},
            {"provider":"host","id":"simple","name":"No reasoning","thinkingLevels":["off"]}
        ],"thinkingLevels":[]})
    );
    assert!(h.store.v2_sessions().unwrap().is_empty());
    assert_eq!(fs::read_to_string(transcript).unwrap(), original);
    assert!(!cwd.join("fixture-session.jsonl").exists());
    assert!(get(&client, &session, &token).await["runtime"].is_null());
    let live = client
        .get(format!("{session}/models"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(live.status(), 409);

    let managed_cwd = h.cwd("other-project");
    let managed = create_session(&client, &h, &token, &managed_cwd).await;
    let catalog = get(&client, &format!("{managed}/model-catalog"), &token).await;
    assert_eq!(catalog["models"][0]["id"], "fast");
    assert!(get(&client, &managed, &token).await["runtime"].is_null());
    assert!(
        !Path::new(&managed_cwd)
            .join("fixture-session.jsonl")
            .exists()
    );
}

#[tokio::test]
async fn invalid_catalog_has_no_fallback_and_missing_workspace_cannot_launch_omp() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let token = h.pair().await;
    let cwd = h.cwd("model-errors");
    let session = create_session(&client, &h, &token, &cwd).await;
    let url = format!("{session}/model-catalog");
    fs::write(
        Path::new(&cwd).join("fixture-model-catalog.json"),
        r#"{"models":[{"id":"bad"}]}"#,
    )
    .unwrap();
    let failed = client.get(&url).bearer_auth(&token).send().await.unwrap();
    assert_eq!(failed.status(), 503);
    assert_eq!(
        failed.json::<Value>().await.unwrap()["code"],
        "model_catalog_unavailable"
    );
    fs::remove_dir_all(cwd).unwrap();
    let forbidden = client.get(&url).bearer_auth(&token).send().await.unwrap();
    assert_eq!(forbidden.status(), 403);
    assert_eq!(
        forbidden.json::<Value>().await.unwrap()["code"],
        "workspace_forbidden"
    );
}

#[tokio::test]
async fn effective_workspace_scope_filters_exact_models_and_rejects_unresolved_selectors() {
    let h = Harness::new(1, vec!["--profile=work".into()]).await;
    let client = reqwest::Client::new();
    let token = h.pair().await;
    let cwd = h.cwd("scoped");
    let session = create_session(&client, &h, &token, &cwd).await;
    let url = format!("{session}/model-catalog");
    let cwd = Path::new(&cwd);
    let trace = cwd.join("fixture-catalog-probes.jsonl");
    fs::write(&trace, "").unwrap();
    fs::create_dir_all(cwd.join(".omp")).unwrap();
    let config = cwd.join(".omp/config.yml");
    fs::write(&config, "enabledModels: [fixture/smart]").unwrap();
    let catalog = get(&client, &url, &token).await;
    assert_eq!(
        catalog["models"],
        json!([{"provider":"fixture","id":"smart","name":"Fixture Smart","thinkingLevels":["off","medium","high"]}])
    );
    let queries: Vec<Value> = fs::read_to_string(&trace)
        .unwrap()
        .lines()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect();
    assert_eq!(
        queries,
        [
            json!([
                "--profile",
                "work",
                "config",
                "get",
                "enabledModels",
                "--json"
            ]),
            json!(["--profile", "work", "models", "--json"]),
        ]
    );

    for selector in ["fixture/*", "@smart", "fixture/missing"] {
        fs::write(
            &config,
            json!({"enabledModels":["fixture/smart", selector]}).to_string(),
        )
        .unwrap();
        let response = client.get(&url).bearer_auth(&token).send().await.unwrap();
        assert_eq!(response.status(), 503);
        let body = response.json::<Value>().await.unwrap();
        assert_eq!(body["code"], "model_catalog_unsupported");
        assert!(body["message"].as_str().unwrap().contains("enabledModels"));
        assert!(body.get("models").is_none());
    }
    fs::write(&trace, "").unwrap();
    fs::write(&config, "enabledModels: invalid").unwrap();
    let response = client.get(&url).bearer_auth(&token).send().await.unwrap();
    assert_eq!(response.status(), 503);
    assert_eq!(
        response.json::<Value>().await.unwrap()["code"],
        "model_catalog_unavailable"
    );
    assert_eq!(fs::read_to_string(trace).unwrap().lines().count(), 1);
    assert!(get(&client, &session, &token).await["runtime"].is_null());
    assert!(!cwd.join("fixture-session.jsonl").exists());
}

#[tokio::test]
async fn unsupported_launch_options_never_probe_or_return_a_partial_catalog() {
    for (flag, value, inline) in [
        ("--trusted-extension", "/absolute/provider.ts", false),
        ("--trusted-extension", "/absolute/provider.ts", true),
        ("--api-key", "private-key-value", false),
        ("--api-key", "private-key-value", true),
        ("--config", "private-overlay.yml", true),
        ("--cwd", "/other-project", false),
    ] {
        let mut args = vec!["--model=fixture/smart".into()];
        if inline {
            args.push(format!("{flag}={value}"));
        } else {
            args.extend([flag.into(), value.into()]);
        }
        let h = Harness::new(1, args).await;
        let client = reqwest::Client::new();
        let token = h.pair().await;
        let cwd = h.cwd("restricted");
        let session = create_session(&client, &h, &token, &cwd).await;
        let trace = Path::new(&cwd).join("fixture-catalog-probes.jsonl");
        fs::write(&trace, "").unwrap();
        let response = client
            .get(format!("{session}/model-catalog"))
            .bearer_auth(&token)
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 503);
        let body = response.json::<Value>().await.unwrap();
        assert_eq!(body["code"], "model_catalog_unsupported");
        assert!(body["message"].as_str().unwrap().contains(flag));
        assert!(!body.to_string().contains(value));
        assert!(body.get("models").is_none());
        assert!(fs::read(trace).unwrap().is_empty());
        assert!(get(&client, &session, &token).await["runtime"].is_null());
        assert!(!Path::new(&cwd).join("fixture-session.jsonl").exists());
    }
}
