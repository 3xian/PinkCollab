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
async fn enter(prompt: &str) -> Result<String> {
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
    tokio::select! {
        result = receive => result.context("input reader stopped")?,
        _ = tokio::signal::ctrl_c() => anyhow::bail!("Setup cancelled."),
    }
}
async fn connect(binary: &Path, non_interactive: bool) -> Result<()> {
    if funnel::state(binary).is_ok_and(|state| state.backend_state == "Running") {
        return Ok(());
    }
    ensure!(
        !non_interactive,
        "Tailscale isn't connected. Sign in to Tailscale before running non-interactive setup."
    );
    println!(
        "Tailscale is installed but isn't connected.\n\nPinkCollab needs Tailscale for secure remote access.\nComplete Tailscale sign-in and PinkCollab will continue automatically."
    );
    let mut child = tokio::process::Command::new(binary)
        .arg("up")
        .kill_on_drop(true)
        .spawn()
        .context("Open the Tailscale app and complete sign-in.")?;
    let mut exited = false;
    loop {
        tokio::select! {
            _ = tokio::signal::ctrl_c() => { anyhow::bail!("Setup cancelled."); }
            _ = tokio::time::sleep(Duration::from_secs(2)) => {
                if funnel::state(binary).is_ok_and(|state| state.backend_state == "Running") { let _ = child.kill().await; return Ok(()); }
                if !exited && let Some(status) = child.try_wait()? {
                    exited = true;
                    if !status.success() { println!("Open the Tailscale app to complete sign-in. Waiting for connection (Ctrl+C to cancel)..."); }
                }
            }
        }
    }
}

pub async fn run(
    dir: &Path,
    requested: &[PathBuf],
    non_interactive: bool,
    transport: &str,
) -> Result<()> {
    ensure!(
        transport == "tailscale",
        "This version supports --transport tailscale only."
    );
    ensure!(
        non_interactive || std::io::stdin().is_terminal(),
        "Interactive setup needs a terminal. For automation use --non-interactive --workspace <directory>."
    );
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
    let installation = crate::service::Installation::new(dir)?;
    installation.check_owner()?;
    let manager = crate::service::manager(&installation);
    let before = manager.status().context(
        "The background service needs attention. Run pinkcollab service status for details.",
    )?;
    if std::net::TcpListener::bind(config.listen).is_err()
        && before != crate::service::ServiceStatus::Running
    {
        anyhow::bail!(
            "The Gateway address is already in use outside the managed service. Stop the manually started Gateway or resolve the conflict, then run setup again."
        );
    }
    manager.preflight(non_interactive)?;
    let roots = requested_roots(requested, &std::env::current_dir()?, non_interactive)?;
    for root in &roots {
        println!("Workspace: {}", root.display());
    }
    if requested.is_empty() && !non_interactive {
        let answer = enter("Use this workspace? [Y/n] ").await?;
        ensure!(
            matches!(
                answer.trim().to_ascii_lowercase().as_str(),
                "" | "y" | "yes"
            ),
            "Setup cancelled. Choose a directory with --workspace <directory>."
        );
    }
    let executable = config::resolve_executable(&config.omp).context(OMP_HELP)?;
    let version = omp::version(&workspace::display(&executable)).await;
    ensure!(
        version != "unavailable" && !version.is_empty(),
        "{OMP_HELP}"
    );
    println!("✓ OMP {}", safe_text(&version));
    let changed = merge(&mut config, roots, &executable)?;
    if changed || !exists {
        save(dir, &config)?;
    }
    Store::open(dir)?;
    println!("✓ Workspaces configured");
    let binary = funnel::tailscale_binary().context("Tailscale is required for this version of PinkCollab.\n\nInstall Tailscale:\nhttps://tailscale.com/download\n\nThen run:\n  pinkcollab setup")?;
    connect(&binary, non_interactive).await?;
    println!("✓ Tailscale connected");
    config.public_url = funnel::reconcile(dir, &config, &binary).context("Secure remote access could not be configured. Check Tailscale permissions with pinkcollab doctor.")?;
    println!("✓ Secure remote access ready");
    manager.install().context(
        "The background service could not be installed. Run pinkcollab service install for details. On Windows, use an administrator terminal as the same user.",
    )?;
    println!("✓ Background service installed");
    manager
        .start()
        .context("The background service could not start. Run pinkcollab doctor for details.")?;
    crate::health::wait(config.listen).await?;
    println!("✓ Gateway running");
    if non_interactive {
        println!("PinkCollab is ready. Run pinkcollab pair to pair a phone.");
        return Ok(());
    }
    pair_and_wait(dir, &config).await?;
    println!("\nPinkCollab is ready.\nYou can close this terminal.");
    Ok(())
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
async fn pair_and_wait(dir: &Path, config: &Config) -> Result<()> {
    let store = Store::open(dir)?;
    loop {
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
            tokio::select! {
                _ = tokio::signal::ctrl_c() => { anyhow::bail!("Pairing cancelled. Gateway remains running; use pinkcollab pair later."); }
                _ = tokio::time::sleep(Duration::from_secs(1)) => {}
            }
        }
        enter("\nPairing code expired.\n\nPress Enter to generate a new one.").await?;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
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
