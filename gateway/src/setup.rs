use crate::setup_control::{Control, Phase};
use anyhow::{Context, Result, ensure};
use pinkcollab_gateway::{
    config::{self, Config},
    funnel, omp,
    storage::{self, Store},
    workspace,
};
use qrcode::{QrCode, render::unicode::Dense1x2};
use std::{
    io::{IsTerminal, Write},
    path::{Path, PathBuf},
    time::Duration,
};

const OMP_HELP: &str = "OMP was not found or could not start.\n\nInstall OMP first and make sure this works:\n\n  omp --version";

#[derive(Clone, Copy, Debug, PartialEq, Eq, clap::ValueEnum)]
pub enum Transport {
    Tailscale,
    External,
}

impl Transport {
    fn validate(self, public_url: Option<&str>) -> Result<()> {
        match self {
            Self::Tailscale => ensure!(
                public_url.is_none(),
                "--public-url is only supported with --transport external"
            ),
            Self::External => {
                let url = public_url
                    .context("--transport external requires --public-url <https://...>")?;
                let parsed = config::root_url(url).context("invalid --public-url")?;
                ensure!(parsed.scheme() == "https", "--public-url requires HTTPS");
            }
        }
        Ok(())
    }
}

fn configure_external(
    dir: &Path,
    config: &mut Config,
    url: &str,
    save_required: bool,
) -> Result<()> {
    let url_changed = config.public_url != url;
    if url_changed {
        config.public_url = url.to_owned();
    }
    if save_required || url_changed {
        save(dir, config)?;
    }
    // Relinquish diagnostic ownership only after persistence succeeds, even for the same URL.
    // The externally managed endpoint may still use the existing Funnel mapping.
    match std::fs::remove_file(dir.join("funnel-url")) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error).context("cannot clear managed Funnel marker"),
    }
    Ok(())
}

fn requested_roots(
    requested: &[PathBuf],
    cwd: &Path,
    non_interactive: bool,
) -> Result<Vec<PathBuf>> {
    ensure!(
        !non_interactive || !requested.is_empty(),
        "Non-interactive setup requires --workspace <directory>."
    );
    let defaults = vec![cwd.to_path_buf()];
    workspace::canonical_roots(if requested.is_empty() {
        &defaults
    } else {
        requested
    })
}
/// Merge values without changing other configuration fields. Return whether a restart is needed.
fn merge(config: &mut Config, roots: Vec<PathBuf>, executable: &Path) -> Result<bool> {
    let old = serde_yaml::to_string(config)?;
    let mut existing = if config.workspaces.is_empty() {
        Vec::new()
    } else {
        workspace::canonical_roots(&config.workspaces)?
    };
    for root in roots {
        if !existing.contains(&root) {
            existing.push(root);
        }
    }
    config.workspaces = existing
        .iter()
        .map(|p| PathBuf::from(workspace::display(p)))
        .collect();
    config.omp = workspace::display(executable);
    Ok(old != serde_yaml::to_string(config)?)
}
fn save(dir: &Path, config: &Config) -> Result<()> {
    storage::private_dir(dir)?;
    storage::replace_private_file(
        &dir.join("config.yaml"),
        serde_yaml::to_string(config)?.as_bytes(),
    )
}
async fn enter(control: &Control, prompt: &str) -> Result<String> {
    print!("{prompt}");
    std::io::stdout().flush()?;
    // A detached reader lets Ctrl+C cancel without Tokio waiting on a blocked stdin task.
    let (send, receive) = tokio::sync::oneshot::channel();
    std::thread::spawn(move || {
        let result = (|| -> Result<String> {
            let mut line = String::new();
            ensure!(
                std::io::stdin().read_line(&mut line)? > 0,
                "Input closed; setup cancelled."
            );
            Ok(line)
        })();
        let _ = send.send(result);
    });
    control
        .wait(async { receive.await.context("input reader stopped")? })
        .await
}

pub async fn run(
    dir: &Path,
    requested: &[PathBuf],
    non_interactive: bool,
    transport: Transport,
    public_url: Option<&str>,
) -> Result<()> {
    transport.validate(public_url)?;
    ensure!(
        non_interactive || std::io::stdin().is_terminal(),
        "Interactive setup needs a terminal. For automation use --non-interactive --workspace <directory>."
    );
    let mut control = Control::new()?;
    println!("PinkCollab Setup\n");
    let exists = dir.join("config.yaml").exists();
    let mut config = if exists {
        Config::load(dir)
            .context("Your existing configuration needs attention; run pinkcollab doctor.")?
    } else {
        Config::default()
    };
    if exists {
        println!("✓ Existing configuration");
    }
    let installation = std::sync::Arc::new(crate::service::Installation::new(dir)?);
    let preflight = installation.clone();
    let before = control.blocking(move || {
        preflight.check_owner()?;
        let manager = crate::service::manager(&preflight);
        manager.preflight(non_interactive)?;
        manager.status().context("The background service needs attention. Run pinkcollab service status for details.")
    }, None).await?;
    if std::net::TcpListener::bind(config.listen).is_err()
        && before != crate::service::ServiceStatus::Running
    {
        anyhow::bail!(
            "The Gateway address is already in use outside the managed service. Stop the manually started Gateway or resolve the conflict, then run setup again."
        );
    }
    control.checkpoint().await?;
    let roots = requested_roots(requested, &std::env::current_dir()?, non_interactive)?;
    for root in &roots {
        println!("Workspace: {}", root.display());
    }
    if requested.is_empty() && !non_interactive {
        let answer = enter(&control, "Use this workspace? [Y/n] ")
            .await
            .context("Setup cancelled. No pairing token was created.")?;
        ensure!(
            matches!(
                answer.trim().to_ascii_lowercase().as_str(),
                "" | "y" | "yes"
            ),
            "Setup cancelled. Choose a directory with --workspace <directory>."
        );
    }
    let executable = config::resolve_executable(&config.omp).context(OMP_HELP)?;
    let version = control
        .wait(async { Ok(omp::version(&workspace::display(&executable)).await) })
        .await?;
    ensure!(
        version != "unavailable" && !version.is_empty(),
        "{OMP_HELP}"
    );
    println!("✓ OMP {}", safe_text(&version));
    control.checkpoint().await?;
    let save_required = merge(&mut config, roots, &executable)? || !exists;
    match transport {
        Transport::External => {
            configure_external(dir, &mut config, public_url.unwrap(), save_required)?;
        }
        Transport::Tailscale if save_required => save(dir, &config)?,
        Transport::Tailscale => {}
    }
    Store::open(dir)?;
    println!("✓ Workspaces configured");
    match transport {
        Transport::Tailscale => {
            config.public_url =
                configure_tailscale(&mut control, dir, &config, non_interactive).await?;
        }
        Transport::External => println!("✓ Externally managed HTTPS access configured"),
    }
    let install = installation.clone();
    let outcome = control
        .blocking(
            move || crate::service::manager(&install).install(),
            Some(Phase::Installed),
        )
        .await?;
    println!("{}", outcome.message());
    println!("Starting Gateway...");
    let start = installation.clone();
    control
        .blocking(move || crate::service::manager(&start).start(), None)
        .await?;
    control.wait(crate::health::wait(config.listen)).await?;
    control.phase = Phase::Running;
    control.checkpoint().await?;
    println!("✓ Gateway running");
    if non_interactive {
        println!("PinkCollab is ready. Run pinkcollab pair to pair a phone.");
        return Ok(());
    }
    let paired = Store::open(dir)?.clients()?.len();
    let answer = if paired > 0 {
        println!("{paired} device(s) already paired.");
        Some(
            enter(&control, "Pair another phone? [y/N] ")
                .await
                .context("Gateway remains running. Pair later with pinkcollab pair.")?,
        )
    } else {
        None
    };
    control.checkpoint().await?;
    if should_pair(paired, answer.as_deref()) {
        pair_and_wait(&control, dir, &config)
            .await
            .context("Gateway remains running. Pair later with pinkcollab pair.")?;
    }
    println!("\nPinkCollab is ready.\nYou can close this terminal.");
    Ok(())
}

async fn configure_tailscale(
    control: &mut Control,
    dir: &Path,
    config: &Config,
    non_interactive: bool,
) -> Result<String> {
    let binary = funnel::tailscale_binary().with_context(|| {
        format!(
            "Tailscale wasn't found.\n\n{}\n\nThen run:\n  pinkcollab setup",
            funnel::installation_help()
        )
    })?;
    println!("Connecting Tailscale...");
    crate::setup_connect::connect(control, &binary, non_interactive).await?;
    control.checkpoint().await?;
    println!("✓ Tailscale connected");
    println!("Configuring secure remote access...");
    let remote_dir = dir.to_path_buf();
    let remote_config = config.clone();
    let url = control
        .blocking(
            move || {
                funnel::reconcile(
                    &remote_dir,
                    &remote_config,
                    &binary,
                    if non_interactive {
                        funnel::Interaction::Forbidden
                    } else {
                        funnel::Interaction::Allowed
                    },
                )
            },
            None,
        )
        .await
        .context(
            "Secure remote access could not be configured. Run pinkcollab doctor for details.",
        )?;
    println!("✓ Secure remote access ready");
    Ok(url)
}
fn should_pair(existing: usize, answer: Option<&str>) -> bool {
    existing == 0
        || answer.is_some_and(|s| matches!(s.trim().to_ascii_lowercase().as_str(), "y" | "yes"))
}
fn safe_text(value: &str) -> String {
    value.chars().flat_map(char::escape_debug).collect()
}
struct PairingAttempt {
    known: Vec<String>,
    payload: String,
    expires: i64,
}
impl PairingAttempt {
    fn new(store: &Store, url: &str) -> Result<Self> {
        Ok(Self {
            known: store.clients()?.into_iter().map(|c| c.id).collect(),
            payload: crate::admin::pairing_payload(store, url)?,
            expires: chrono::Utc::now().timestamp() + 300,
        })
    }
    fn completed(&self, store: &Store) -> Result<Option<String>> {
        Ok(store
            .clients()?
            .into_iter()
            .find(|c| !self.known.contains(&c.id))
            .map(|c| c.name))
    }
    fn remaining(&self, now: i64) -> u64 {
        self.expires.saturating_sub(now).max(0) as u64
    }
}
async fn pair_and_wait(control: &Control, dir: &Path, config: &Config) -> Result<()> {
    let store = Store::open(dir)?;
    loop {
        control.checkpoint().await?;
        let attempt = PairingAttempt::new(&store, &config.public_url)?;
        let code = QrCode::new(attempt.payload.as_bytes())?;
        println!(
            "\nScan this QR code with PinkCollab:\n\n{}",
            code.render::<Dense1x2>().quiet_zone(true).build()
        );
        loop {
            if let Some(name) = attempt.completed(&store)? {
                println!("\r✓ {} paired                          ", safe_text(&name));
                return Ok(());
            }
            let remaining = attempt.remaining(chrono::Utc::now().timestamp());
            if remaining == 0 {
                break;
            }
            print!(
                "\rWaiting for your phone... {}:{:02} ",
                remaining / 60,
                remaining % 60
            );
            std::io::stdout().flush()?;
            control
                .wait(async {
                    tokio::time::sleep(Duration::from_secs(1)).await;
                    Ok(())
                })
                .await?;
        }
        enter(control, "\nPairing code expired.\n\nPress Enter to generate a new one, or Ctrl+C to finish setup and pair later with:\n\n  pinkcollab pair\n").await?;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn external_requires_https_even_for_development_hosts() {
        for url in [
            "https://pink.example.com",
            "https://1.2.3.4",
            "https://pink.example.com:8443",
            "https://pink.example.com/",
        ] {
            Transport::External.validate(Some(url)).unwrap();
        }
        for url in [
            "http://1.2.3.4",
            "http://127.0.0.1:8787",
            "http://localhost",
            "http://10.0.2.2:8787",
        ] {
            assert!(Transport::External.validate(Some(url)).is_err(), "{url}");
        }
    }

    #[test]
    fn external_persists_url_updates_and_relinquishes_managed_funnel() {
        let data = tempfile::tempdir().unwrap();
        let workspace = tempfile::tempdir().unwrap();
        let mut config = Config {
            workspaces: vec![workspace.path().to_path_buf()],
            ..Config::default()
        };
        for url in ["https://pink.example.com", "https://new.example.com:8443"] {
            Transport::External.validate(Some(url)).unwrap();
            configure_external(data.path(), &mut config, url, false).unwrap();
            config = Config::load(data.path()).unwrap();
            assert_eq!(config.public_url, url);
        }
        std::fs::write(data.path().join("funnel-url"), &config.public_url).unwrap();
        let before = std::fs::read(data.path().join("config.yaml")).unwrap();
        let url = config.public_url.clone();
        configure_external(data.path(), &mut config, &url, false).unwrap();
        assert!(!data.path().join("funnel-url").exists());
        assert!(matches!(
            crate::remote::inspect(data.path(), &config),
            crate::remote::Access::External
        ));
        assert_eq!(
            std::fs::read(data.path().join("config.yaml")).unwrap(),
            before
        );
    }
    #[test]
    fn failed_external_save_preserves_managed_funnel_ownership() {
        let data = tempfile::tempdir().unwrap();
        let old_url = "https://old-host.ts.net";
        let marker = data.path().join("funnel-url");
        std::fs::write(&marker, old_url).unwrap();
        // A directory at the destination deterministically prevents atomic replacement.
        std::fs::create_dir(data.path().join("config.yaml")).unwrap();
        let mut config = Config {
            public_url: old_url.into(),
            ..Config::default()
        };
        assert!(
            configure_external(data.path(), &mut config, "https://proxy.example", false).is_err()
        );
        assert_eq!(std::fs::read_to_string(&marker).unwrap(), old_url);
    }
    #[test]
    fn pairing_decision() {
        assert!(should_pair(0, None));
        assert!(!should_pair(1, None));
        assert!(!should_pair(1, Some("\n")));
        assert!(should_pair(1, Some("yes")));
    }
    #[test]
    fn workspace_defaults_and_explicit_requirements() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(
            requested_roots(&[], dir.path(), false).unwrap(),
            vec![dir.path().canonicalize().unwrap()]
        );
        assert!(requested_roots(&[], dir.path(), true).is_err());
        assert_eq!(
            requested_roots(
                &[dir.path().into(), dir.path().into()],
                Path::new("/missing"),
                true
            )
            .unwrap()
            .len(),
            1
        );
    }
    #[test]
    fn fresh_and_repeated_config_preserve_settings_and_merge() {
        let data = tempfile::tempdir().unwrap();
        let a = tempfile::tempdir().unwrap();
        let b = tempfile::tempdir().unwrap();
        let mut config = Config {
            name: "Keep me".into(),
            max_sessions: 3,
            ..Config::default()
        };
        let executable = std::env::current_exe().unwrap();
        assert!(
            merge(
                &mut config,
                vec![a.path().canonicalize().unwrap()],
                &executable
            )
            .unwrap()
        );
        save(data.path(), &config).unwrap();
        let mut config = Config::load(data.path()).unwrap();
        assert!(
            merge(
                &mut config,
                vec![b.path().canonicalize().unwrap()],
                &executable
            )
            .unwrap()
        );
        assert!(
            !merge(
                &mut config,
                vec![b.path().canonicalize().unwrap()],
                &executable
            )
            .unwrap()
        );
        assert_eq!(config.omp, workspace::display(&executable));
        assert_eq!(config.workspaces.len(), 2);
        assert_eq!(config.name, "Keep me");
        assert_eq!(config.max_sessions, 3);
    }
    #[test]
    fn missing_omp_has_actionable_message() {
        let error = config::resolve_executable("/missing/pinkcollab-test/omp")
            .context(OMP_HELP)
            .unwrap_err();
        assert!(error.to_string().contains("omp --version"));
    }
    #[test]
    fn pairing_wait_detects_new_devices_and_expiry_without_printing_credentials() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let old = store.new_pairing().unwrap();
        store.pair(&old, "Existing phone").unwrap();
        let attempt = PairingAttempt::new(&store, "https://host.ts.net").unwrap();
        assert!(attempt.completed(&store).unwrap().is_none());
        assert_eq!(attempt.remaining(attempt.expires), 0);
        assert_eq!(attempt.remaining(attempt.expires + 10), 0);
        let payload: serde_json::Value = serde_json::from_str(&attempt.payload).unwrap();
        assert_eq!(payload["version"], 1);
        let token = payload["token"].as_str().unwrap();
        store.pair(token, "Pixel 10").unwrap();
        assert_eq!(
            attempt.completed(&store).unwrap().as_deref(),
            Some("Pixel 10")
        );
        assert!(store.pair(token, "Replay").is_err());
    }
}
