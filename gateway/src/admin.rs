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
    validate_pairing_url(&url)?;
    let token = store.new_pairing()?;
    let payload = serde_json::json!({
        "version": 1,
        "url": url.trim_end_matches('/'),
        "token": token,
    })
    .to_string();
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

/// Preflight checks for a Gateway that is not currently running.
pub async fn status(dir: &Path) -> Result<()> {
    let config = Config::load(dir)?;
    let mut problems = Vec::new();
    println!("config: {}", dir.join("config.yaml").display());
    println!("listen: {}", config.listen);
    if config.public_url.trim().is_empty() {
        println!("public URL: unset");
    } else if let Err(error) = validate_pairing_url(&config.public_url) {
        println!("public URL: {} (invalid)", config.public_url);
        problems.push(format!("invalid public URL: {error:#}"));
    } else {
        println!("public URL: {}", config.public_url);
    }
    for root in &config.workspaces {
        match root.canonicalize() {
            Ok(path) if path.is_dir() => println!("workspace: {} (ok)", path.display()),
            Ok(path) => {
                println!("workspace: {} (not a directory)", path.display());
                problems.push(format!("workspace is not a directory: {}", root.display()));
            }
            Err(error) => {
                println!("workspace: {} (unavailable: {error})", root.display());
                problems.push(format!("workspace unavailable: {}", root.display()));
            }
        }
    }
    match config::resolve_executable(&config.omp) {
        Some(executable) => {
            let executable = workspace::display(&executable);
            let version = omp::version(&executable).await;
            println!("omp: {executable} ({version})");
            if version == "unavailable" {
                problems.push(format!("OMP did not answer `{executable} --version`"));
            }
        }
        None => {
            println!("omp: {} (unavailable)", config.omp);
            problems.push(format!("OMP executable is unavailable: {}", config.omp));
        }
    }
    // Informational: a loopback development setup needs no front end at all.
    match funnel::state(Path::new(funnel::TAILSCALE)) {
        Ok(state) => match state.dns_name {
            Some(host) => println!("tailscale: {} ({host})", state.backend_state),
            None => println!("tailscale: {}", state.backend_state),
        },
        Err(error) => println!("tailscale: unavailable ({error:#})"),
    }
    match TcpListener::bind(config.listen) {
        Ok(_) => println!("listen port: free"),
        Err(error) => {
            println!("listen port: unavailable ({error})");
            problems.push(format!("cannot bind {}: {error}", config.listen));
        }
    }
    match Store::counts(dir) {
        Ok(counts) => {
            println!("paired devices: {}", counts.clients);
            println!("recorded sessions: {}", counts.sessions);
        }
        Err(error) => problems.push(format!("cannot read Gateway database: {error:#}")),
    }
    if problems.is_empty() {
        println!("status: ready");
        return Ok(());
    }
    for problem in &problems {
        println!("problem: {problem}");
    }
    std::io::stdout().flush()?;
    anyhow::bail!("{} problem(s) found", problems.len())
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
