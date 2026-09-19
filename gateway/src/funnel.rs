use crate::{config::Config, storage};
use anyhow::{Context, Result, ensure};
use std::{path::Path, process::Command};

pub const TAILSCALE: &str = "tailscale";
/// Ports Tailscale accepts for a public Funnel HTTPS endpoint.
const FUNNEL_PORTS: [u16; 3] = [443, 8443, 10000];

pub struct Options<'a> {
    pub https_port: u16,
    pub dry_run: bool,
    pub binary: Option<&'a Path>,
}
pub fn allows_https_port(port: u16) -> bool {
    FUNNEL_PORTS.contains(&port)
}
/// The loopback backend Funnel forwards to.
pub fn target(port: u16) -> String {
    format!("http://127.0.0.1:{port}")
}
/// The public URL the phone dials.
pub fn public_url(host: &str, https_port: u16) -> String {
    if https_port == 443 {
        format!("https://{host}")
    } else {
        format!("https://{host}:{https_port}")
    }
}
fn status(binary: &Path) -> Result<serde_json::Value> {
    let output = Command::new(binary).args(["status", "--json"]).output().with_context(|| {
        format!(
            "cannot run `{}`; install Tailscale from https://tailscale.com/download or pass --tailscale <path>",
            binary.display()
        )
    })?;
    ensure!(
        output.status.success(),
        "`tailscale status` failed; run `tailscale up` and log in first"
    );
    serde_json::from_slice(&output.stdout).context("cannot parse `tailscale status --json`")
}
fn node(status: &serde_json::Value) -> Result<&str> {
    let state = status["BackendState"].as_str().unwrap_or("unknown");
    ensure!(
        state == "Running",
        "Tailscale is not connected (state `{state}`); run `tailscale up` and log in first"
    );
    status["Self"]["DNSName"]
        .as_str()
        .map(|v| v.trim_end_matches('.'))
        .filter(|v| !v.is_empty())
        .context("cannot read this node's tailnet DNS name; enable MagicDNS in the admin console")
}
/// Publish the loopback Gateway on the internet through Tailscale Funnel.
///
/// Funnel only solves reachability: pairing, bearer credentials, revocation and the
/// workspace allowlist keep protecting the Gateway exactly as they do on a private network.
pub fn setup(dir: &Path, config: &Config, options: Options<'_>) -> Result<()> {
    let port = config.listen.port();
    ensure!(
        allows_https_port(options.https_port),
        "Funnel serves HTTPS on 443, 8443 or 10000 only"
    );
    ensure!(
        config.listen.ip().is_loopback(),
        "Funnel forwards to 127.0.0.1; set listen: 127.0.0.1:{port}"
    );
    let binary = options.binary.unwrap_or(Path::new(TAILSCALE));
    let target = target(port);
    // A dry run has to work before Tailscale is up, so fall back to a placeholder name.
    let host = match status(binary).and_then(|s| node(&s).map(str::to_owned)) {
        Ok(host) => host,
        Err(e) if options.dry_run => {
            println!("note: {e:#}");
            "<hostname>.ts.net".into()
        }
        Err(e) => return Err(e),
    };
    let public = public_url(&host, options.https_port);
    println!("Funnel: {public} -> {target}");
    if options.dry_run {
        println!(
            "dry run, nothing changed; this would run:\n  {} funnel --bg --https={} --yes {target}\nand set public_url: {public} in {}",
            binary.display(),
            options.https_port,
            dir.join("config.yaml").display()
        );
        return Ok(());
    }
    let output = Command::new(binary)
        .args([
            "funnel",
            "--bg",
            &format!("--https={}", options.https_port),
            "--yes",
            &target,
        ])
        .output()
        .with_context(|| format!("cannot run `{}`", binary.display()))?;
    ensure!(
        output.status.success(),
        "`tailscale funnel` failed: {}{}",
        String::from_utf8_lossy(&output.stdout).trim(),
        String::from_utf8_lossy(&output.stderr).trim()
    );
    let report = String::from_utf8_lossy(&output.stdout);
    let report = report.trim();
    if !report.is_empty() {
        println!("{report}");
    }
    let path = dir.join("config.yaml");
    set_public_url(&path, &public)?;
    println!(
        "public_url set to {public} in {}\n\
         This URL is reachable from the internet: keep pairing single-use, keep credentials revocable,\n\
         and do not rely on the URL being hard to guess.\n\
         Next: pinkcollab-gateway pair",
        path.display()
    );
    Ok(())
}
/// Rewrite only the public_url line, so hand-written comments survive.
fn set_public_url(path: &Path, url: &str) -> Result<()> {
    let text = std::fs::read_to_string(path).context("run init --workspace <directory> first")?;
    let line = format!("public_url: {url}");
    let mut lines: Vec<&str> = text.lines().collect();
    match lines
        .iter()
        .position(|l| l.trim_start().starts_with("public_url:"))
    {
        Some(i) => lines[i] = &line,
        None => lines.push(&line),
    }
    storage::private_file(path, format!("{}\n", lines.join("\n")).as_bytes())
}
