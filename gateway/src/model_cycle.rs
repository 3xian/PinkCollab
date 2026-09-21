use crate::model::{ModelChoice, ModelInfo};
use anyhow::{Context, Result, bail};
use serde::Deserialize;
use serde_json::Value;
use std::{collections::HashMap, ffi::OsString, path::Path, time::Duration};
use tokio::process::Command;

const DEFAULT_CYCLE_ORDER: [&str; 3] = ["smol", "default", "slow"];
const THINKING_LEVELS: [&str; 9] = [
    "off", "minimal", "low", "medium", "high", "xhigh", "max", "ultra", "auto",
];

#[derive(Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct OmpModelSettings {
    model_roles: HashMap<String, String>,
    cycle_order: Option<Vec<String>>,
    model_provider_order: Vec<String>,
}

/// Ask OMP for its merged settings instead of maintaining a second settings loader in Gateway.
pub async fn choices(
    executable: &str,
    args: &[String],
    cwd: &Path,
    available: Vec<ModelInfo>,
    current: Option<&ModelInfo>,
) -> Result<Vec<ModelChoice>> {
    let settings = effective_settings(executable, args, cwd).await?;
    let order = settings.cycle_order.unwrap_or_else(|| {
        DEFAULT_CYCLE_ORDER
            .iter()
            .map(|role| (*role).to_owned())
            .collect()
    });

    Ok(order
        .into_iter()
        .filter_map(|role| {
            let configured = settings.model_roles.get(&role).map(String::as_str);
            let (selector, thinking_level) = match configured {
                Some(value) => split_thinking_level(value),
                None if role == "default" => {
                    let model = current?;
                    return Some(ModelChoice::from_model(model.clone(), role, None));
                }
                None => return None,
            };
            let (selector, thinking_level) =
                resolve_alias(selector, thinking_level, &settings.model_roles, 0)?;
            let model = match_model(selector, &available, &settings.model_provider_order)?;
            Some(ModelChoice::from_model(model.clone(), role, thinking_level))
        })
        .collect())
}

async fn effective_settings(
    executable: &str,
    args: &[String],
    cwd: &Path,
) -> Result<OmpModelSettings> {
    let mut command = Command::new(executable);
    command
        .args(["config", "list", "--json"])
        .current_dir(cwd)
        .kill_on_drop(true);
    if let Some(overlays) = config_overlays(args)? {
        command.env("PI_CONFIG_FILES", overlays);
    }
    let output = tokio::time::timeout(Duration::from_secs(5), command.output())
        .await
        .context("OMP settings query timed out")?
        .with_context(|| format!("cannot query OMP settings with {executable}"))?;
    if !output.status.success() {
        let error = String::from_utf8_lossy(&output.stderr).trim().to_owned();
        bail!(
            "OMP settings query failed{}",
            if error.is_empty() {
                String::new()
            } else {
                format!(": {error}")
            }
        );
    }
    let document: Value = serde_json::from_slice(&output.stdout)
        .context("OMP returned invalid JSON for effective settings")?;
    let mut settings = OmpModelSettings {
        model_roles: setting(&document, "modelRoles").unwrap_or_default(),
        cycle_order: setting(&document, "cycleOrder"),
        model_provider_order: setting(&document, "modelProviderOrder").unwrap_or_default(),
    };
    apply_runtime_model_overrides(&mut settings, args);
    Ok(settings)
}

fn setting<T: for<'de> Deserialize<'de>>(document: &Value, name: &str) -> Option<T> {
    serde_json::from_value(document.get(name)?.get("value")?.clone()).ok()
}

/// Config subcommands do not accept launch-only `--config`, so mirror those overlays through
/// OMP's canonical PI_CONFIG_FILES channel when reading the effective configuration.
fn config_overlays(args: &[String]) -> Result<Option<OsString>> {
    let mut paths = std::env::var_os("PI_CONFIG_FILES")
        .map(|value| std::env::split_paths(&value).collect::<Vec<_>>())
        .unwrap_or_default();
    let mut index = 0;
    while index < args.len() {
        if args[index] == "--config" {
            let path = args
                .get(index + 1)
                .context("OMP --config requires a path")?;
            paths.push(path.into());
            index += 2;
            continue;
        }
        if let Some(path) = args[index].strip_prefix("--config=") {
            paths.push(path.into());
        }
        index += 1;
    }
    if paths.is_empty() {
        Ok(None)
    } else {
        Ok(Some(
            std::env::join_paths(paths).context("OMP config overlay path is invalid")?,
        ))
    }
}

fn apply_runtime_model_overrides(settings: &mut OmpModelSettings, args: &[String]) {
    for (role, variable) in [
        ("smol", "PI_SMOL_MODEL"),
        ("slow", "PI_SLOW_MODEL"),
        ("plan", "PI_PLAN_MODEL"),
    ] {
        if let Ok(value) = std::env::var(variable)
            && !value.trim().is_empty()
        {
            settings.model_roles.insert(role.into(), value);
        }
    }
    let mut index = 0;
    while index < args.len() {
        for (flag, role) in [
            ("--model", "default"),
            ("--smol", "smol"),
            ("--slow", "slow"),
            ("--plan", "plan"),
        ] {
            if args[index] == flag {
                if let Some(value) = args.get(index + 1) {
                    settings.model_roles.insert(role.into(), value.clone());
                    index += 1;
                }
                break;
            }
            if let Some(value) = args[index].strip_prefix(&format!("{flag}=")) {
                settings.model_roles.insert(role.into(), value.into());
                break;
            }
        }
        index += 1;
    }
}

fn split_thinking_level(value: &str) -> (&str, Option<String>) {
    let Some((selector, suffix)) = value.rsplit_once(':') else {
        return (value, None);
    };
    if THINKING_LEVELS.contains(&suffix) {
        (selector, Some(suffix.to_owned()))
    } else {
        (value, None)
    }
}

fn resolve_alias<'a>(
    selector: &'a str,
    thinking_level: Option<String>,
    roles: &'a HashMap<String, String>,
    depth: usize,
) -> Option<(&'a str, Option<String>)> {
    if depth > 8 {
        return None;
    }
    let role = selector
        .strip_prefix('@')
        .or_else(|| selector.strip_prefix("pi/"));
    let Some(role) = role else {
        return Some((selector, thinking_level));
    };
    let (next, alias_thinking_level) = split_thinking_level(roles.get(role)?);
    resolve_alias(
        next,
        thinking_level.or(alias_thinking_level),
        roles,
        depth + 1,
    )
}

fn match_model<'a>(
    selector: &str,
    models: &'a [ModelInfo],
    provider_order: &[String],
) -> Option<&'a ModelInfo> {
    if let Some((provider, id)) = selector.split_once('/') {
        return models
            .iter()
            .find(|model| model.provider == provider && model.id == id);
    }
    let matches = models
        .iter()
        .filter(|model| model.id == selector)
        .collect::<Vec<_>>();
    provider_order
        .iter()
        .find_map(|provider| {
            matches
                .iter()
                .find(|model| model.provider == *provider)
                .copied()
        })
        .or_else(|| matches.first().copied())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn model(provider: &str, id: &str) -> ModelInfo {
        ModelInfo {
            provider: provider.into(),
            id: id.into(),
            name: id.into(),
            role: None,
            thinking_level: None,
        }
    }

    #[test]
    fn role_values_separate_thinking_suffixes_only_when_known() {
        assert_eq!(
            split_thinking_level("openai/gpt:high"),
            ("openai/gpt", Some("high".into()))
        );
        assert_eq!(
            split_thinking_level("openai/gpt:preview"),
            ("openai/gpt:preview", None)
        );
    }

    #[test]
    fn aliases_inherit_thinking_level_and_allow_an_explicit_override() {
        let roles = HashMap::from([("slow".to_owned(), "openai/gpt:high".to_owned())]);
        assert_eq!(
            resolve_alias("@slow", None, &roles, 0),
            Some(("openai/gpt", Some("high".into())))
        );
        assert_eq!(
            resolve_alias("@slow", Some("xhigh".into()), &roles, 0),
            Some(("openai/gpt", Some("xhigh".into())))
        );
    }

    #[test]
    fn provider_order_resolves_ambiguous_bare_model_ids() {
        let models = vec![model("openai", "gpt"), model("azure", "gpt")];
        assert_eq!(
            match_model("gpt", &models, &["azure".into()])
                .unwrap()
                .provider,
            "azure"
        );
    }

    #[test]
    fn config_output_is_read_from_effective_values() {
        let document = serde_json::json!({
            "modelRoles": {"value": {"default": "openai/gpt"}},
            "cycleOrder": {"value": ["default"]},
            "modelProviderOrder": {"value": ["openai"]}
        });
        let settings = OmpModelSettings {
            model_roles: setting(&document, "modelRoles").unwrap(),
            cycle_order: setting(&document, "cycleOrder"),
            model_provider_order: setting(&document, "modelProviderOrder").unwrap(),
        };
        assert_eq!(settings.model_roles["default"], "openai/gpt");
        assert_eq!(settings.cycle_order.unwrap(), ["default"]);
        assert_eq!(settings.model_provider_order, ["openai"]);
    }
}
