//! Read-only discovery of model capabilities; current settings belong to the live runtime only.
use super::{ApiError, App};
use crate::model::ModelInfo;
use anyhow::{Context, Result, ensure};
use axum::{
    Extension, Json,
    extract::{Path, State},
    http::StatusCode,
    routing::{MethodRouter, get},
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    path::Path as FsPath,
    process::{Command, Stdio},
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::sync::Semaphore;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Catalog {
    models: Vec<ModelInfo>,
    thinking_levels: Vec<String>,
}

pub(super) fn route() -> MethodRouter<App> {
    get(read).layer(Extension(Arc::new(Semaphore::new(2))))
}

async fn read(
    State(app): State<App>,
    Extension(slots): Extension<Arc<Semaphore>>,
    Path(id): Path<String>,
) -> Result<Json<Catalog>, ApiError> {
    let permit = slots.try_acquire_owned().map_err(|_| {
        ApiError(
            StatusCode::SERVICE_UNAVAILABLE,
            "model_catalog_busy",
            "Other model catalogs are loading. Please retry.".into(),
        )
    })?;
    let cwd = app
        .sessions
        .model_catalog_cwd(&id)
        .await
        .map_err(unavailable)?
        .ok_or_else(|| {
            ApiError(
                StatusCode::NOT_FOUND,
                "session_not_found",
                "Session not found".into(),
            )
        })?;
    // Keep the admission slot with the process owner: cancelling HTTP must not allow
    // another client to launch a replacement while the first process is still running.
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        let cwd = app.browser.validate(FsPath::new(&cwd)).map_err(|_| {
            ApiError(
                StatusCode::FORBIDDEN,
                "workspace_forbidden",
                "Session workspace is outside the allowed roots or unavailable".into(),
            )
        })?;
        // Resolve PATH and relative launcher entries on the host, not in the project's cwd.
        let executable = crate::config::resolve_executable(app.sessions.omp_executable())
            .context("configured OMP executable unavailable")
            .map_err(unavailable)?;
        let (mut settings, mut models) = catalog_commands(&executable, app.sessions.omp_args())?;
        let cwd = crate::workspace::display(&cwd);
        let deadline = Instant::now() + Duration::from_secs(30);
        let run = |cmd: &mut Command, limit| {
            cmd.current_dir(&cwd)
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::null());
            crate::omp::finite_output(
                cmd,
                deadline.saturating_duration_since(Instant::now()),
                limit,
            )
            .map_err(unavailable)
        };
        let scope: ModelScope = serde_json::from_slice(&run(&mut settings, 1024 * 1024)?)
            .context("invalid OMP enabledModels response")
            .map_err(unavailable)?;
        let mut catalog = decode(&run(&mut models, 8 * 1024 * 1024)?).map_err(unavailable)?;
        scope.restrict(&mut catalog)?;
        Ok(Json(catalog))
    })
    .await
    .map_err(|error| unavailable(error.into()))?
}

fn unavailable(error: anyhow::Error) -> ApiError {
    eprintln!("Model catalog read failed: {error:#}");
    ApiError(
        StatusCode::SERVICE_UNAVAILABLE,
        "model_catalog_unavailable",
        "Unable to load models. Check that OMP supports config get and models --json.".into(),
    )
}

fn unsupported(reason: &str) -> ApiError {
    ApiError(
        StatusCode::SERVICE_UNAVAILABLE,
        "model_catalog_unsupported",
        format!("{reason} Use the live model list in an attached session."),
    )
}

fn catalog_commands(executable: &FsPath, args: &[String]) -> Result<(Command, Command), ApiError> {
    // Check all tokens before interpreting any option values. Neither a trusted module nor a
    // CLI-only credential can safely be translated into a normal models-subcommand argument.
    for arg in args {
        match arg.split_once('=').map_or(arg.as_str(), |(flag, _)| flag) {
            "--trusted-extension" => {
                return Err(unsupported(
                    "Detached browsing cannot enforce --trusted-extension.",
                ));
            }
            "--api-key" => {
                return Err(unsupported(
                    "Detached browsing cannot use a runtime-only --api-key.",
                ));
            }
            // `config get` has no --config flag. Synthesizing PI_CONFIG_FILES here would
            // shadow overlays that OMP itself discovers later from profile/project .env.
            "--config" => {
                return Err(unsupported(
                    "Detached browsing cannot verify --config overlays. Use PI_CONFIG_FILES instead.",
                ));
            }
            "--cwd" => {
                return Err(unsupported(
                    "Detached browsing cannot use a --cwd override.",
                ));
            }
            _ => {}
        }
    }
    let args = args.split(|arg| arg == "--").next().unwrap_or_default();
    let mut settings = Command::new(executable);
    let mut models = Command::new(executable);
    if let Some(profile) = crate::omp::configured_profile(args) {
        settings.args(["--profile", &profile]);
        models.args(["--profile", &profile]);
    }
    settings.args(["config", "get", "enabledModels", "--json"]);
    models.args(["models", "--json"]);
    let mut args = args.iter().map(String::as_str);
    while let Some(arg) = args.next() {
        let (flag, inline) = arg
            .split_once('=')
            .map_or((arg, None), |(k, v)| (k, Some(v)));
        let flag = match flag {
            "--no-extensions" => {
                models.arg(flag);
                continue;
            }
            "--extension" | "--hook" | "-e" => "--extension",
            _ => continue,
        };
        let value = inline
            .or_else(|| args.next())
            .filter(|v| !v.is_empty() && !v.starts_with('-'))
            .ok_or_else(|| unsupported("Missing or ambiguous OMP catalog option value."))?;
        models.args([flag, value]);
    }
    Ok((settings, models))
}

#[derive(Deserialize)]
#[serde(tag = "key", content = "value")]
enum ModelScope {
    #[serde(rename = "enabledModels")]
    Enabled(Vec<String>),
}

impl ModelScope {
    fn restrict(self, catalog: &mut Catalog) -> Result<(), ApiError> {
        let Self::Enabled(enabled) = self;
        if enabled.is_empty() {
            return Ok(());
        }
        // A literal, present provider/id is authoritative in OMP's matcher. An absent id,
        // glob, role, routed or fuzzy selector needs runtime metadata unavailable in the CLI
        // listing. Never guess its meaning or return a partially matched allowlist.
        let unresolved =
            || unsupported("Detached browsing cannot resolve this enabledModels selection.");
        let mut selectors = HashMap::with_capacity(enabled.len());
        for mut pattern in enabled {
            if !pattern.contains('/')
                || pattern.starts_with("pi/")
                || !pattern.is_ascii()
                || pattern.contains(['*', '?', '[', '@', ':'])
                || pattern.bytes().any(|b| b.is_ascii_whitespace())
            {
                return Err(unresolved());
            }
            pattern.make_ascii_lowercase();
            selectors.insert(pattern, 0usize);
        }
        let mut key = String::new();
        catalog.models.retain(|m| {
            if m.provider.contains('/') {
                return false; // Not addressable by a literal provider/id selector.
            }
            key.clear();
            key.push_str(&m.provider);
            key.push('/');
            key.push_str(&m.id);
            if key.is_ascii() {
                key.make_ascii_lowercase();
            } else {
                key = key.to_lowercase();
            }
            let Some(matches) = selectors.get_mut(&key) else {
                return false;
            };
            *matches += 1;
            true
        });
        if selectors.values().any(|count| *count != 1) {
            return Err(unresolved());
        }
        Ok(())
    }
}

#[derive(Deserialize)]
struct CliCatalog {
    models: Vec<CliModel>,
}

#[derive(Deserialize)]
struct CliModel {
    provider: String,
    id: String,
    name: String,
    reasoning: Option<bool>,
    // `models --json` reports a flat array or null, unlike the RPC model descriptor.
    thinking: Option<Vec<String>>,
}
fn decode(bytes: &[u8]) -> Result<Catalog> {
    let catalog: CliCatalog = serde_json::from_slice(bytes).context("invalid OMP model catalog")?;
    let models = catalog
        .models
        .into_iter()
        .map(|m| {
            ensure!(
                !m.provider.is_empty() && !m.id.is_empty(),
                "OMP returned a model without an identity"
            );
            let thinking_levels = m
                .thinking
                .or_else(|| (m.reasoning == Some(false)).then(Vec::new))
                .map(|mut levels| {
                    if !levels.iter().any(|s| s == "off") {
                        levels.insert(0, "off".into());
                    }
                    levels
                });
            Ok(ModelInfo {
                provider: m.provider,
                id: m.id,
                name: m.name,
                thinking_levels,
                thinking_level: None,
                fast_mode_enabled: None,
                fast_mode_active: None,
            })
        })
        .collect::<Result<_>>()?;
    Ok(Catalog {
        models,
        thinking_levels: Vec::new(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catalog_command_forwards_only_workspace_model_options() {
        let args = [
            "--session-dir",
            "/some/session-dir",
            "--profile=work",
            "--model",
            "unrelated",
            "-e",
            "./custom.ts",
            "--no-extensions",
            "--extension=another.ts",
            "--hook",
            "hook.ts",
        ]
        .map(String::from);
        let (settings, cmd) = catalog_commands(FsPath::new("omp"), &args)
            .unwrap_or_else(|error| panic!("{}", error.2));
        assert_eq!(
            settings.get_args().collect::<Vec<_>>(),
            [
                "--profile",
                "work",
                "config",
                "get",
                "enabledModels",
                "--json"
            ]
        );
        assert_eq!(
            cmd.get_args().collect::<Vec<_>>(),
            [
                "--profile",
                "work",
                "models",
                "--json",
                "--extension",
                "./custom.ts",
                "--no-extensions",
                "--extension",
                "another.ts",
                "--extension",
                "hook.ts",
            ]
        );
    }
}
