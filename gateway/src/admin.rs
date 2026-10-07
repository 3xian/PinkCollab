use anyhow::{Context, Result, ensure};
use chrono::{DateTime, Utc};
use pinkcollab_gateway::{
    config::{self, Config},
    funnel, omp,
    storage::{self, Store},
    workspace,
};
use qrcode::{QrCode, render::unicode::Dense1x2};
use std::{
    io::{IsTerminal, Write},
    net::TcpListener,
    path::{Path, PathBuf},
};

pub fn init(dir: &Path, requested: &[PathBuf]) -> Result<()> {
    let roots = workspace::canonical_roots(requested)?;
    storage::private_dir(dir)?;
    let path = dir.join("config.yaml");
    ensure!(
        !path.exists(),
        "{} exists; edit it or remove it before reinitializing",
        path.display()
    );
    let mut config = Config::default();
    let executable = config::resolve_executable(&config.omp)
        .context("cannot find `omp` on PATH; install OMP before running init")?;
    config.workspaces = roots
        .into_iter()
        .map(|root| PathBuf::from(workspace::display(&root)))
        .collect();
    config.omp = workspace::display(&executable);
    let yaml = serde_yaml::to_string(&config)?;
    storage::private_file(&path, yaml.as_bytes())?;
    Store::open(dir)?;
    println!("Wrote {}", path.display());
    println!("OMP: {}", config.omp);
    println!("Next: run the `status` command with this executable");
    Ok(())
}

fn pairing_url(config: &Config, explicit: Option<String>) -> Result<String> {
    match explicit {
        Some(url) => Ok(url),
        None if !config.public_url.trim().is_empty() => Ok(config.public_url.clone()),
        None => anyhow::bail!(
            "no public pairing URL configured; run `funnel` or pass `pair --url <url>`"
        ),
    }
}

fn validate_pairing_url(raw: &str) -> Result<()> {
    let parsed = config::root_url(raw).context("pair requires a Gateway root URL")?;
    ensure!(
        parsed.scheme() == "https"
            || (parsed.scheme() == "http"
                && ["127.0.0.1", "localhost", "10.0.2.2"]
                    .contains(&parsed.host_str().unwrap_or_default())),
        "pair URL requires HTTPS; HTTP is for loopback/emulator development only"
    );
    Ok(())
}

fn write_qr_png(code: &QrCode, path: &Path) -> Result<()> {
    let image = code
        .render::<image::Luma<u8>>()
        .quiet_zone(true)
        .min_dimensions(384, 384)
        .build();
    let mut png = std::io::Cursor::new(Vec::new());
    image.write_to(&mut png, image::ImageFormat::Png)?;
    storage::private_file(path, &png.into_inner())
}

pub fn pair(dir: &Path, url: Option<String>, qr: Option<PathBuf>) -> Result<()> {
    let config = Config::load(dir)?;
    let store = Store::open(dir)?;
    issue_pairing(&config, &store, url, qr)
}

fn issue_pairing(
    config: &Config,
    store: &Store,
    url: Option<String>,
    qr: Option<PathBuf>,
) -> Result<()> {
    let url = pairing_url(config, url)?;
    let payload = pairing_payload(store, &url)?;
    let terminal = std::io::stderr().is_terminal();
    let code = if terminal || qr.is_some() {
        Some(QrCode::new(payload.as_bytes())?)
    } else {
        None
    };
    if let Some(path) = &qr {
        write_qr_png(
            code.as_ref()
                .expect("QR code exists when an image path was requested"),
            path,
        )?;
    }
    println!("{payload}");
    if terminal {
        eprintln!(
            "{}",
            code.as_ref()
                .expect("QR code exists for terminal output")
                .render::<Dense1x2>()
                .quiet_zone(true)
                .build()
        );
    }
    if let Some(path) = qr {
        eprintln!("QR image: {}", path.display());
    }
    Ok(())
}

pub(crate) fn pairing_payload(store: &Store, url: &str) -> Result<String> {
    validate_pairing_url(url)?;
    let token = store.new_pairing()?;
    let payload = serde_json::json!({
        "version": 1,
        "url": url.trim_end_matches('/'),
        "token": token,
    })
    .to_string();
    Ok(payload)
}

pub fn clients(dir: &Path) -> Result<()> {
    let store = Store::open(dir)?;
    let clients = store.clients()?;
    if clients.is_empty() {
        println!("No paired devices.");
        return Ok(());
    }
    for client in clients {
        let paired = client
            .created_at
            .and_then(|timestamp| DateTime::<Utc>::from_timestamp(timestamp, 0))
            .map_or_else(|| "unknown".into(), |value| value.to_rfc3339());
        let name: String = client.name.chars().flat_map(char::escape_debug).collect();
        println!("{}  {}  paired {}", client.id, name, paired);
    }
    Ok(())
}

pub fn leases(dir: &Path) -> Result<()> {
    let store = Store::open(dir)?;
    let leases = store.runtime_leases()?;
    if leases.is_empty() {
        println!("No runtime leases.");
    } else {
        for (session, generation) in leases {
            println!("{session} {generation}");
        }
    }
    Ok(())
}

pub fn clear_lease(
    dir: &Path,
    session: &str,
    generation: &str,
    verified_exited: bool,
) -> Result<()> {
    ensure!(
        verified_exited,
        "--verified-exited is required after checking the old OMP process and descendants are gone"
    );
    let config = Config::load(dir)?;
    let _listener = TcpListener::bind(config.listen)
        .context("stop the Gateway before clearing a runtime lease")?;
    let store = Store::open(dir)?;
    ensure!(
        store.release_runtime(session, generation)?,
        "no matching runtime lease"
    );
    println!("Cleared lease for {session} {generation}");
    Ok(())
}

pub fn revoke(dir: &Path, client: &str) -> Result<()> {
    let store = Store::open(dir)?;
    ensure!(
        store.revoke(client)?,
        "no paired client with id {client}; run the `clients` command with this executable"
    );
    println!("Revoked {client}");
    Ok(())
}

pub fn setup_funnel(dir: &Path, options: funnel::Options<'_>, with_pair: bool) -> Result<()> {
    let pair_store = (with_pair && !options.dry_run)
        .then(|| Store::open(dir))
        .transpose()?;
    let config = Config::load(dir)?;
    let Some(url) = funnel::setup(dir, &config, options)? else {
        if with_pair {
            println!("dry run: no pairing code was minted");
        }
        return Ok(());
    };
    let Some(store) = pair_store else {
        println!("Next: run the `pair` command with this executable");
        return Ok(());
    };
    issue_pairing(&config, &store, Some(url), None)
}

pub async fn default_entry(dir: &Path) -> Result<()> {
    if !dir.join("config.yaml").try_exists()? {
        println!("PinkCollab is not set up yet.\n\nRun:\n\n  pinkcollab setup");
        return Ok(());
    }
    status(dir).await
}

/// Concise operational status; a running listener is expected.
pub async fn status(dir: &Path) -> Result<()> {
    let config = Config::load(dir)?;
    let running = crate::health::healthy(config.listen).await;
    let version = omp::version(&config.omp).await;
    let remote = crate::remote::inspect(dir, &config);
    let service = crate::service::Installation::new(dir)
        .and_then(|installation| {
            installation.check_owner()?;
            crate::service::manager(&installation).status()
        })
        .unwrap_or_else(|e| crate::service::ServiceStatus::Failed(format!("{e:#}")));
    let counts = Store::counts(dir)?;
    println!(
        "PinkCollab\n\nGateway       {}\nService       {:?}\nRemote access {}\nOMP           {}\nWorkspaces    {}\nDevices       {}\nSessions      {}",
        if running {
            "Running"
        } else {
            "Stopped or unavailable"
        },
        service,
        remote.label(),
        version,
        config.workspaces.len(),
        counts.clients,
        counts.sessions
    );
    if running && service != crate::service::ServiceStatus::Running {
        println!("Gateway is running outside the managed background service.");
    }
    ensure!(
        running
            && service == crate::service::ServiceStatus::Running
            && !remote.failed()
            && version != "unavailable",
        "Run `pinkcollab doctor` for details."
    );
    Ok(())
}
/// Full diagnostics continue through independent failures, including an invalid config.
pub async fn doctor(dir: &Path) -> Result<()> {
    let mut problems = Vec::new();
    println!(
        "CLI\n  current executable: {}\n  version: {}",
        std::env::current_exe()?.display(),
        env!("CARGO_PKG_VERSION")
    );
    if let Ok(version) = std::env::var("PINKCOLLAB_NPM_VERSION") {
        println!("  npm launcher version: {version}");
    }
    println!("Config\n  path: {}", dir.join("config.yaml").display());
    let config = match Config::load(dir) {
        Ok(config) => {
            println!("  parsed: yes");
            Some(config)
        }
        Err(error) => {
            println!("  parsed: no ({error:#})");
            problems.push("Config");
            None
        }
    };
    println!("OMP");
    let executable = config.as_ref().map_or("omp", |c| c.omp.as_str());
    match config::resolve_executable(executable) {
        Some(path) => {
            let version = omp::version(&workspace::display(&path)).await;
            println!("  executable: {}\n  version: {version}", path.display());
            if version == "unavailable" {
                problems.push("OMP");
            }
        }
        None => {
            println!("  executable: {executable} (unavailable)");
            problems.push("OMP");
        }
    }
    let remote = config
        .as_ref()
        .map(|config| crate::remote::inspect(dir, config));
    println!("Tailscale");
    if matches!(remote, Some(crate::remote::Access::External)) {
        println!("  not required for externally managed access");
    } else {
        match funnel::tailscale_binary() {
            Some(binary) => {
                println!("  binary: {}", binary.display());
                match funnel::state(&binary) {
                    Ok(state) => println!(
                        "  backend: {}\n  dns: {}",
                        state.backend_state,
                        state.dns_name.as_deref().unwrap_or("unavailable")
                    ),
                    Err(error) => println!("  unavailable: {error:#}"),
                }
            }
            None => println!("  binary: unavailable (optional for externally managed access)"),
        }
    }
    println!("Funnel");

    if let (Some(config), Some(remote)) = (&config, &remote) {
        println!("  public URL: {}\n  {}", config.public_url, remote.label());
        if let crate::remote::Access::Failed(error) = &remote {
            println!("  {error}");
            problems.push("Remote access");
        }
    }
    println!("Service");
    #[cfg(windows)]
    println!("  mode: current-user login startup (stops on logout; no password required)");
    match crate::service::Installation::new(dir) {
        Ok(installation) => {
            println!(
                "  binary: {}\n  definition: {}",
                installation.binary.display(),
                installation.definition.display()
            );
            println!("  data dir: {}", installation.dir.display());
            let state = installation
                .check_owner()
                .and_then(|()| crate::service::manager(&installation).status());
            match installation.binary_identity() {
                Ok(identity) => {
                    let current = identity.current;
                    if let Some(installed) = identity.installed {
                        println!(
                            "  installed binary SHA-256: {installed}\n  CLI binary SHA-256: {current}"
                        );
                        if installed != current {
                            println!(
                                "The background Gateway is from another PinkCollab build.\n\nRun:\n\n  pinkcollab setup"
                            );
                            problems.push("Service build");
                        } else {
                            println!("  build: current");
                        }
                    } else {
                        println!("  installed binary: missing (not staged)");
                        if !matches!(&state, Ok(crate::service::ServiceStatus::NotInstalled)) {
                            problems.push("Service binary missing");
                        }
                    }
                }
                Err(error) => {
                    println!("  fingerprint: unavailable ({error:#})");
                    problems.push("Service build");
                }
            }
            match state {
                Ok(state) => {
                    println!("  state: {state:?}");
                    if state != crate::service::ServiceStatus::Running {
                        problems.push("Service");
                    }
                }
                Err(error) => {
                    println!("  state: unavailable ({error:#})");
                    problems.push("Service");
                }
            }
        }
        Err(error) => {
            println!("  unavailable: {error:#}");
            problems.push("Service");
        }
    }
    #[cfg(target_os = "linux")]
    {
        let user = std::env::var("USER").unwrap_or_default();
        let linger = std::process::Command::new("loginctl")
            .args(["show-user", &user, "--property=Linger", "--value"])
            .output();
        if !linger
            .is_ok_and(|o| o.status.success() && String::from_utf8_lossy(&o.stdout).trim() == "yes")
        {
            println!(
                "  note: user service may stop after logout; ask your administrator about user lingering"
            );
        }
    }
    println!("Gateway");
    if let Some(config) = &config {
        let healthy = crate::health::healthy(config.listen).await;
        println!("  listen: {}\n  local health: {healthy}", config.listen);
        if !healthy {
            problems.push("Gateway");
            println!(
                "  listen port: {}",
                if TcpListener::bind(config.listen).is_ok() {
                    "free (Gateway stopped)"
                } else {
                    "occupied by an unverified listener"
                }
            );
        }
        for root in &config.workspaces {
            println!(
                "  workspace: {} ({})",
                root.display(),
                if root.is_dir() {
                    "available"
                } else {
                    "unavailable"
                }
            );
            if !root.is_dir() {
                problems.push("Workspace");
            }
        }
    } else {
        println!("  local health: unknown (invalid config)");
    }
    println!("Database");
    match Store::counts(dir) {
        Ok(counts) => {
            println!(
                "  clients: {}\n  sessions: {}",
                counts.clients, counts.sessions
            );
            match Store::open(dir).and_then(|s| s.runtime_leases()) {
                Ok(leases) => println!("  leases: {}", leases.len()),
                Err(error) => {
                    println!("  leases: unavailable ({error:#})");
                    problems.push("Database leases");
                }
            }
        }
        Err(error) => {
            println!("  unavailable: {error:#}");
            problems.push("Database");
        }
    }
    std::io::stdout().flush()?;
    ensure!(
        problems.is_empty(),
        "Problems found: {}",
        problems.join(", ")
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pairing_url_requires_an_explicit_or_configured_endpoint() {
        let mut config = Config::default();
        assert!(pairing_url(&config, None).is_err());
        config.public_url = "https://gateway.example".into();
        assert_eq!(
            pairing_url(&config, None).unwrap(),
            "https://gateway.example"
        );
        assert_eq!(
            pairing_url(&config, Some("https://override.example".into())).unwrap(),
            "https://override.example"
        );
    }
}
