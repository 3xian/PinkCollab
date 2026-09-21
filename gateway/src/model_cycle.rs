use crate::model::{ModelChoice, ModelInfo};
use serde::Deserialize;
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
};

const DEFAULT_CYCLE_ORDER: [&str; 3] = ["smol", "default", "slow"];
const THINKING_LEVELS: [&str; 9] = [
    "off", "minimal", "low", "medium", "high", "xhigh", "max", "ultra", "auto",
];

#[derive(Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct OmpModelSettings {
    model_roles: HashMap<String, String>,
    cycle_order: Option<Vec<String>>,
}

pub fn choices(
    cwd: &Path,
    available: Vec<ModelInfo>,
    current: Option<&ModelInfo>,
) -> Vec<ModelChoice> {
    let settings = effective_settings(cwd);
    let order = settings.cycle_order.unwrap_or_else(|| {
        DEFAULT_CYCLE_ORDER
            .iter()
            .map(|role| (*role).to_owned())
            .collect()
    });

    order
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
            let model = match_model(selector, &available)?;
            Some(ModelChoice::from_model(model.clone(), role, thinking_level))
        })
        .collect()
}

fn effective_settings(cwd: &Path) -> OmpModelSettings {
    let mut effective = OmpModelSettings::default();
    for path in [
        global_config_path(),
        Some(cwd.join(".omp").join("config.yml")),
    ]
    .into_iter()
    .flatten()
    {
        let Some(layer) = read_settings(&path) else {
            continue;
        };
        effective.model_roles.extend(layer.model_roles);
        if layer.cycle_order.is_some() {
            effective.cycle_order = layer.cycle_order;
        }
    }
    effective
}

fn global_config_path() -> Option<PathBuf> {
    let home = dirs::home_dir()?;
    let root = std::env::var_os("PI_CONFIG_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(".omp"));
    let root = if root.is_absolute() {
        root
    } else {
        home.join(root)
    };
    let profile = std::env::var("OMP_PROFILE")
        .or_else(|_| std::env::var("PI_PROFILE"))
        .ok()
        .filter(|profile| !profile.trim().is_empty());
    Some(match profile {
        Some(profile) => root
            .join("profiles")
            .join(profile)
            .join("agent")
            .join("config.yml"),
        None => root.join("agent").join("config.yml"),
    })
}

fn read_settings(path: &Path) -> Option<OmpModelSettings> {
    let bytes = std::fs::read(path)
        .or_else(|_| std::fs::read(path.with_extension("yaml")))
        .ok()?;
    serde_yaml::from_slice(&bytes).ok()
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

fn match_model<'a>(selector: &str, models: &'a [ModelInfo]) -> Option<&'a ModelInfo> {
    if let Some((provider, id)) = selector.split_once('/') {
        models
            .iter()
            .find(|model| model.provider == provider && model.id == id)
    } else {
        let mut matches = models.iter().filter(|model| model.id == selector);
        let first = matches.next()?;
        matches.next().is_none().then_some(first)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
