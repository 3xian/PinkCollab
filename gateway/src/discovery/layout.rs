//! OMP 18.4.2 storage selection, kept separate from scanning. Environment is
//! injected so profile precedence can be tested without mutating process globals.
use std::{collections::HashMap, path::PathBuf};

pub(super) struct Layout {
    pub root: Option<PathBuf>,
    pub flat: bool,
}
impl Layout {
    pub fn from_process(args: &[String]) -> Self {
        Self::resolve(
            args,
            dirs::home_dir(),
            &std::env::vars_os()
                .filter_map(|(k, v)| Some((k.into_string().ok()?, v.into_string().ok()?)))
                .collect(),
            cfg!(any(target_os = "linux", target_os = "macos")),
        )
    }
    fn resolve(
        args: &[String],
        home: Option<PathBuf>,
        env: &HashMap<String, String>,
        xdg: bool,
    ) -> Self {
        let explicit = option(args, "--session-dir");
        let flat = explicit.is_some();
        let root = (|| {
            if let Some(path) = explicit {
                return Some(PathBuf::from(path));
            }
            let home = home?;
            let selected = option(args, "--profile")
                .or_else(|| env.get("OMP_PROFILE").cloned())
                .or_else(|| env.get("PI_PROFILE").cloned());
            let profile = profile_name(selected.as_deref()).ok()?;
            let config = env
                .get("PI_CONFIG_DIR")
                .filter(|s| !s.is_empty())
                .map_or(".omp", String::as_str);
            // OMP defines this override relative to home. Other layouts should use
            // an absolute --session-dir instead of guessing a different root.
            if PathBuf::from(config).is_absolute() {
                return None;
            }
            let base = home.join(config);
            let config = profile
                .as_ref()
                .map_or_else(|| base.clone(), |p| base.join("profiles").join(p));
            let default_agent = config.join("agent");
            let mut agent = default_agent.clone();
            if profile.is_none()
                && let Some(value) = env.get("PI_CODING_AGENT_DIR").filter(|s| !s.is_empty())
            {
                let candidate = PathBuf::from(value);
                // Explicit default selection must not inherit a named profile's
                // automatically exported PI_CODING_AGENT_DIR.
                let inherited = profile_name(
                    env.get("OMP_PROFILE")
                        .or_else(|| env.get("PI_PROFILE"))
                        .map(String::as_str),
                )
                .ok()
                .flatten()
                .or_else(|| {
                    profile_name(env.get("PI_PROFILE").map(String::as_str))
                        .ok()
                        .flatten()
                })
                .is_some_and(|p| candidate == base.join("profiles").join(p).join("agent"));
                if !inherited {
                    agent = candidate;
                }
            }
            if xdg
                && agent == default_agent
                && let Some(data) = env.get("XDG_DATA_HOME").filter(|s| !s.is_empty())
            {
                let root = PathBuf::from(data).join("omp");
                let root = profile
                    .as_ref()
                    .map_or_else(|| root.clone(), |p| root.join("profiles").join(p));
                if root.is_dir() {
                    return Some(root.join("sessions"));
                }
            }
            Some(agent.join("sessions"))
        })()
        .filter(|p| p.is_absolute());
        Self { root, flat }
    }
}

fn option(args: &[String], name: &str) -> Option<String> {
    args.iter()
        .enumerate()
        .filter_map(|(i, arg)| {
            if arg == name {
                args.get(i + 1).cloned()
            } else {
                arg.strip_prefix(&format!("{name}=")).map(str::to_owned)
            }
        })
        .next_back()
}

fn profile_name(value: Option<&str>) -> Result<Option<String>, ()> {
    let name = value.unwrap_or_default().trim();
    if name.is_empty() || name == "default" {
        return Ok(None);
    }
    let stem = name.split('.').next().unwrap_or_default();
    let reserved = matches!(stem, "con" | "prn" | "aux" | "nul")
        || ((stem.starts_with("com") || stem.starts_with("lpt"))
            && stem.len() == 4
            && stem.as_bytes()[3].is_ascii_digit());
    if name.len() > 64
        || name.ends_with('.')
        || reserved
        || !name.as_bytes()[0].is_ascii_alphanumeric()
        || !name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b"._-".contains(&b))
    {
        return Err(());
    }
    Ok(Some(name.into()))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn profile_and_config_precedence_matches_omp() {
        let home = tempfile::tempdir().unwrap();
        for (args, pairs, suffix) in [
            (vec![], vec![], ".omp/agent/sessions"),
            (
                vec![],
                vec![("PI_PROFILE", "work.dev")],
                ".omp/profiles/work.dev/agent/sessions",
            ),
            (
                vec![],
                vec![("PI_PROFILE", "work"), ("OMP_PROFILE", "")],
                ".omp/agent/sessions",
            ),
            (
                vec![],
                vec![("PI_PROFILE", "work"), ("OMP_PROFILE", " other.dev ")],
                ".omp/profiles/other.dev/agent/sessions",
            ),
            (
                vec!["--profile=cli.dev"],
                vec![("OMP_PROFILE", "env"), ("PI_CONFIG_DIR", ".custom")],
                ".custom/profiles/cli.dev/agent/sessions",
            ),
            (
                vec!["--profile", "default"],
                vec![("PI_PROFILE", "work"), ("PI_CONFIG_DIR", ".custom")],
                ".custom/agent/sessions",
            ),
        ] {
            let args = args.into_iter().map(String::from).collect::<Vec<_>>();
            let env = pairs
                .into_iter()
                .map(|(k, v)| (k.into(), v.into()))
                .collect();
            assert_eq!(
                Layout::resolve(&args, Some(home.path().into()), &env, false).root,
                Some(home.path().join(suffix))
            );
        }
        for name in [
            "../escape",
            "Work",
            "con",
            "con.foo",
            "lpt1",
            "bad.",
            "_bad",
        ] {
            assert!(profile_name(Some(name)).is_err(), "{name}");
        }
    }
    #[test]
    fn explicit_directory_and_xdg_override_rules() {
        let home = tempfile::tempdir().unwrap();
        let data = tempfile::tempdir().unwrap();
        let custom = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(data.path().join("omp/profiles/work.dev")).unwrap();
        let mut env = HashMap::from([(
            "XDG_DATA_HOME".into(),
            data.path().to_string_lossy().into_owned(),
        )]);
        let resolve = |env: &HashMap<String, String>| {
            Layout::resolve(&[], Some(home.path().into()), env, true)
                .root
                .unwrap()
        };
        assert_eq!(resolve(&env), data.path().join("omp/sessions"));
        env.insert(
            "PI_CODING_AGENT_DIR".into(),
            custom.path().to_string_lossy().into_owned(),
        );
        assert_eq!(resolve(&env), custom.path().join("sessions"));
        env.insert("OMP_PROFILE".into(), "work.dev".into());
        assert_eq!(
            resolve(&env),
            data.path().join("omp/profiles/work.dev/sessions")
        );
        env.insert("OMP_PROFILE".into(), "unmigrated".into());
        assert_eq!(
            resolve(&env),
            home.path().join(".omp/profiles/unmigrated/agent/sessions")
        );
        let layout = Layout::resolve(
            &[
                "--session-dir".into(),
                custom.path().to_string_lossy().into_owned(),
            ],
            None,
            &env,
            true,
        );
        assert!(layout.flat);
        assert_eq!(layout.root, Some(custom.path().into()));
    }

    #[test]
    fn returning_to_default_drops_profile_derived_agent_override() {
        let home = tempfile::tempdir().unwrap();
        let derived = home
            .path()
            .join(".omp/profiles/work/agent")
            .to_string_lossy()
            .into_owned();
        for (args, pairs) in [
            (
                vec!["--profile".into(), "default".into()],
                vec![("OMP_PROFILE", "work")],
            ),
            (vec![], vec![("OMP_PROFILE", ""), ("PI_PROFILE", "work")]),
        ] {
            let mut env: HashMap<String, String> = pairs
                .into_iter()
                .map(|(k, v)| (k.into(), v.into()))
                .collect();
            env.insert("PI_CODING_AGENT_DIR".into(), derived.clone());
            assert_eq!(
                Layout::resolve(&args, Some(home.path().into()), &env, false).root,
                Some(home.path().join(".omp/agent/sessions"))
            );
        }
    }
}
