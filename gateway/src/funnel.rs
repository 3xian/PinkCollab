use crate::{config::Config, storage};
use anyhow::{Context, Result, ensure};
use std::{
    path::{Path, PathBuf},
    process::Command,
    time::Duration,
};

pub const TAILSCALE: &str = "tailscale";
/// Platform-specific installation guidance, shared by setup and CLI diagnostics.
pub fn installation_help() -> &'static str {
    if cfg!(target_os = "macos") {
        "On macOS, we recommend the Homebrew CLI:\n\n  brew install tailscale\n  sudo brew services start tailscale\n  tailscale up\n  tailscale status\n\nComplete Tailscale sign-in before rerunning setup. If you already use Tailscale.app, you can keep using it."
    } else {
        "Install Tailscale from https://tailscale.com/download and make sure `tailscale status` works."
    }
}
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
    let output = crate::command::output(
        Command::new(binary).args(["status", "--json"]),
        Duration::from_secs(10),
    )
    .with_context(|| {
        format!(
            "cannot run `{}`; pass --tailscale <path> if installed elsewhere.\n\n{}",
            binary.display(),
            installation_help()
        )
    })?;
    let value: serde_json::Value =
        serde_json::from_slice(&output.stdout).context("cannot parse `tailscale status --json`")?;
    ensure!(
        output.status.success() || value["BackendState"].is_string(),
        "`tailscale status` failed; run `tailscale up` and log in first"
    );
    Ok(value)
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
pub struct NodeStatus {
    pub backend_state: String,
    pub dns_name: Option<String>,
}
/// Tailscale connection state for diagnostics. This does not imply that Serve or Funnel is set up.
pub fn state(binary: &Path) -> Result<NodeStatus> {
    let status = status(binary)?;
    Ok(NodeStatus {
        backend_state: status["BackendState"]
            .as_str()
            .unwrap_or("unknown")
            .to_owned(),
        dns_name: status["Self"]["DNSName"]
            .as_str()
            .map(|value| value.trim_end_matches('.'))
            .filter(|value| !value.is_empty())
            .map(str::to_owned),
    })
}
/// Publish the loopback Gateway on the internet through Tailscale Funnel.
///
/// Funnel only solves reachability: pairing, bearer credentials, revocation and the
/// workspace allowlist keep protecting the Gateway exactly as they do on a private network.
///
/// Returns the public URL it published, or `None` when this was a dry run.
pub fn setup(dir: &Path, config: &Config, options: Options<'_>) -> Result<Option<String>> {
    apply(dir, config, options)
}
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Interaction {
    Allowed,
    Forbidden,
}
pub fn reconcile(
    dir: &Path,
    config: &Config,
    binary: &Path,
    interaction: Interaction,
) -> Result<String> {
    reconcile_with_timeout(dir, config, binary, interaction, apply_timeout(interaction))
}
fn reconcile_with_timeout(
    dir: &Path,
    config: &Config,
    binary: &Path,
    interaction: Interaction,
    timeout: Duration,
) -> Result<String> {
    ensure!(
        config.listen.ip() == std::net::IpAddr::V4(std::net::Ipv4Addr::LOCALHOST),
        "Tailscale setup requires the Gateway's IPv4 local address. Keep your custom listener with manual setup."
    );
    let host = node(&status(binary)?)?.to_owned();
    let port = if config.public_url.is_empty() {
        443
    } else {
        let url = crate::config::root_url(&config.public_url)?;
        ensure!(
            url.host_str()
                .is_some_and(|name| name == host || name.ends_with(".ts.net"))
                && url.scheme() == "https",
            "Existing remote address belongs to another transport. Keep it with manual setup, or update the configuration before using Tailscale setup."
        );
        url.port().unwrap_or(443)
    };
    let public = public_url(&host, port);
    let value = inspect_configuration(binary)?;
    if mapping_matches(&value, &host, port, config.listen.port()) {
        set_public_url(&dir.join("config.yaml"), &public)?;
        return Ok(public);
    }
    if config.public_url.is_empty() {
        let key = format!("{host}:{port}");
        let handlers = &value["Web"][&key]["Handlers"];
        ensure!(
            handlers.is_null()
                || (handlers.as_object().is_some_and(|h| h.len() == 1)
                    && handlers["/"]["Proxy"].as_str()
                        == Some(target(config.listen.port()).as_str())),
            "Tailscale is already sharing another application at this address. Use manual setup to choose a separate remote-access port."
        );
        ensure!(
            value["TCP"][port.to_string()].is_null()
                || value["TCP"][port.to_string()]["HTTPS"] == true,
            "Tailscale is already forwarding another application on this port. Use manual setup to choose a separate remote-access port."
        );
    }
    ensure!(
        allows_https_port(port),
        "Funnel serves HTTPS on 443, 8443 or 10000 only"
    );
    let applied =
        match publish_with_timeout(binary, port, config.listen.port(), interaction, timeout) {
            Err(error) if error.is::<crate::command::Interrupted>() => {
                return Err(error.context("Setup cancelled. No further setup steps were started."));
            }
            result => result,
        };
    let retry = if interaction == Interaction::Forbidden {
        "pinkcollab setup --non-interactive --workspace <directory> (with your original options)"
    } else {
        "pinkcollab setup (with your original options)"
    };
    // A command may time out after the daemon committed its configuration.
    // Only the exact desired mapping can turn an ambiguous result into success.
    let verified = inspect(binary, &host, port, config.listen.port());
    if !matches!(verified, Ok(true)) {
        applied.with_context(|| approval_help(port, config.listen.port(), retry))?;
        verified?;
        anyhow::bail!(
            "Tailscale has not enabled the requested remote access. {}",
            approval_help(port, config.listen.port(), retry)
        );
    }
    set_public_url(&dir.join("config.yaml"), &public)?;
    Ok(public)
}
/// Verify both public exposure and the exact HTTP backend, not merely a DNS name.
pub fn inspect(binary: &Path, host: &str, port: u16, backend: u16) -> Result<bool> {
    Ok(mapping_matches(
        &inspect_configuration(binary)?,
        host,
        port,
        backend,
    ))
}
fn inspect_configuration(binary: &Path) -> Result<serde_json::Value> {
    let output = crate::command::output(
        Command::new(binary).args(["funnel", "status", "--json"]),
        Duration::from_secs(10),
    )
    .context("cannot inspect remote access")?;
    ensure!(
        output.status.success(),
        "cannot inspect Tailscale Funnel: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    serde_json::from_slice(&output.stdout).context("cannot parse Tailscale Funnel configuration")
}
fn mapping_matches(value: &serde_json::Value, host: &str, port: u16, backend: u16) -> bool {
    let key = format!("{host}:{port}");
    value["AllowFunnel"][&key] == true
        && value["TCP"][port.to_string()]["HTTPS"] == true
        && value["Web"][&key]["Handlers"]["/"]["Proxy"].as_str() == Some(target(backend).as_str())
}
fn apply(dir: &Path, config: &Config, options: Options<'_>) -> Result<Option<String>> {
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
        return Ok(None);
    }
    let retry = format!(
        "pinkcollab funnel --https={} (with your original --data-dir, --tailscale and other options)",
        options.https_port
    );
    let output = publish_with_timeout(
        binary,
        options.https_port,
        port,
        Interaction::Forbidden,
        apply_timeout(Interaction::Forbidden),
    )
    .with_context(|| approval_help(options.https_port, port, &retry))?;
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
         and do not rely on the URL being hard to guess.",
        path.display()
    );
    Ok(Some(public))
}
fn approval_help(https: u16, backend: u16, retry: &str) -> String {
    format!(
        "Tailscale may be waiting for one-time Funnel/HTTPS approval. Run in a terminal:\n\n  tailscale funnel --bg --https={https} {}\n\nComplete the Tailscale approval, then rerun:\n\n  {retry}",
        target(backend)
    )
}
fn apply_timeout(interaction: Interaction) -> Duration {
    Duration::from_secs(if interaction == Interaction::Allowed {
        300
    } else {
        30
    })
}

fn publish_with_timeout(
    binary: &Path,
    https: u16,
    backend: u16,
    interaction: Interaction,
    timeout: Duration,
) -> Result<std::process::Output> {
    let mut command = Command::new(binary);
    command.args(["funnel", "--bg", &format!("--https={https}")]);
    let run = if interaction == Interaction::Allowed {
        crate::command::interactive_output
    } else {
        command.arg("--yes");
        crate::command::output
    };
    command.arg(target(backend));
    let output = run(&mut command, timeout).with_context(|| {
        format!(
            "Cannot configure Tailscale Funnel using `{}`",
            binary.display()
        )
    })?;
    if !output.status.success() && funnel_policy_denied(&String::from_utf8_lossy(&output.stderr)) {
        anyhow::bail!(
            "Tailscale is connected, but Funnel is not allowed for this device.\n\nEnable the funnel node attribute for this device in Tailscale, then retry your original command.\n\nAlternatively, use a manual HTTPS deployment described in docs/deployment.md"
        );
    }
    ensure!(
        output.status.success(),
        "`tailscale funnel` failed: {}{}\nCheck the Tailscale output above and Funnel/HTTPS permissions in the admin console.",
        String::from_utf8_lossy(&output.stdout).trim(),
        String::from_utf8_lossy(&output.stderr).trim()
    );
    Ok(output)
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
    storage::replace_private_file(path, format!("{}\n", lines.join("\n")).as_bytes())?;
    storage::replace_private_file(&path.parent().unwrap().join("funnel-url"), url.as_bytes())
}

pub fn tailscale_binary() -> Option<PathBuf> {
    if let Some(binary) = crate::config::resolve_executable("tailscale") {
        return Some(binary);
    }
    #[cfg(target_os = "macos")]
    {
        crate::config::resolve_executable("/Applications/Tailscale.app/Contents/MacOS/Tailscale")
    }
    #[cfg(windows)]
    {
        std::env::var_os("ProgramFiles").and_then(|root| {
            crate::config::resolve_executable(
                &PathBuf::from(root)
                    .join("Tailscale/tailscale.exe")
                    .to_string_lossy(),
            )
        })
    }
    #[cfg(not(any(target_os = "macos", windows)))]
    {
        None
    }
}

#[cfg(test)]
mod onboarding_tests {
    use super::*;
    #[test]
    fn mapping_requires_public_https_and_exact_backend() {
        let mut value = serde_json::json!({
            "TCP": {"443": {"HTTPS": true}},
            "Web": {"host.ts.net:443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:8787"}}}},
            "AllowFunnel": {"host.ts.net:443": true}
        });
        assert!(mapping_matches(&value, "host.ts.net", 443, 8787));
        assert!(!mapping_matches(&value, "host.ts.net", 443, 8888));
        value["AllowFunnel"]["host.ts.net:443"] = false.into();
        assert!(!mapping_matches(&value, "host.ts.net", 443, 8787));
        assert!(!mapping_matches(
            &serde_json::json!({}),
            "host.ts.net",
            443,
            8787
        ));
    }
}

/// Exact diagnostic from tailscale/ipn/serve.go::NodeCanFunnel:
/// https://github.com/tailscale/tailscale/blob/main/ipn/serve.go
/// Local daemon permissions, HTTPS prerequisites and port policy are different errors.
fn funnel_policy_denied(stderr: &str) -> bool {
    stderr.lines().any(|line| {
        let line = line.trim().strip_prefix("error: ").unwrap_or(line.trim());
        line == "Funnel not available; \"funnel\" node attribute not set. See https://tailscale.com/s/no-funnel."
    })
}
#[cfg(test)]
mod policy_tests {
    use super::*;
    #[test]
    fn only_known_node_policy_diagnostic_is_classified() {
        let denied = "Funnel not available; \"funnel\" node attribute not set. See https://tailscale.com/s/no-funnel.";
        assert!(funnel_policy_denied(denied));
        assert!(funnel_policy_denied(&format!("error: {denied}\n")));
        for other in [
            "tailscale funnel: access denied",
            "tailscale funnel: permission denied opening local socket",
            "port 443 is not allowed for funnel",
            "Funnel not available; HTTPS must be enabled. See https://tailscale.com/s/https.",
            "Funnel not enabled: unknown reason",
        ] {
            assert!(!funnel_policy_denied(other), "{other}");
        }
    }
}

#[cfg(all(test, unix))]
#[path = "funnel_tests.rs"]
mod execution_tests;
