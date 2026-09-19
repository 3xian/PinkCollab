use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};
use std::{
    net::SocketAddr,
    path::{Path, PathBuf},
};
use url::Url;
/// A gateway root URL: http or https, no credentials, no path, query or fragment.
pub fn root_url(raw: &str) -> Result<Url> {
    let parsed = Url::parse(raw)
        .context("URL must be absolute, e.g. https://my-host.example-tailnet.ts.net")?;
    ensure!(
        matches!(parsed.scheme(), "http" | "https"),
        "URL requires http or https"
    );
    ensure!(
        parsed.username().is_empty()
            && parsed.password().is_none()
            && parsed.path() == "/"
            && parsed.query().is_none()
            && parsed.fragment().is_none(),
        "URL must be a gateway root URL"
    );
    Ok(parsed)
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Config {
    pub listen: SocketAddr,
    pub public_url: String,
    pub name: String,
    pub workspaces: Vec<PathBuf>,
    pub omp: String,
    pub omp_args: Vec<String>,
    pub tls_cert: Option<PathBuf>,
    pub tls_key: Option<PathBuf>,
    pub max_sessions: usize,
}
impl Default for Config {
    fn default() -> Self {
        Self {
            listen: "127.0.0.1:8787".parse().unwrap(),
            public_url: String::new(),
            name: std::env::var("COMPUTERNAME")
                .or_else(|_| std::env::var("HOSTNAME"))
                .unwrap_or_else(|_| "my-host".into()),
            workspaces: vec![],
            omp: "omp".into(),
            omp_args: vec![],
            tls_cert: None,
            tls_key: None,
            max_sessions: 8,
        }
    }
}
pub fn data_dir() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join(".pinkcollab")
}
impl Config {
    pub fn load(dir: &Path) -> Result<Self> {
        let mut c: Self = serde_yaml::from_slice(
            &std::fs::read(dir.join("config.yaml"))
                .context("run init --workspace <directory> first")?,
        )?;
        ensure!(!c.workspaces.is_empty(), "at least one workspace required");
        ensure!(
            (1..=100).contains(&c.max_sessions),
            "max_sessions must be 1..100"
        );
        ensure!(
            c.tls_cert.is_some() == c.tls_key.is_some(),
            "tls_cert and tls_key required together"
        );
        ensure!(
            c.listen.ip().is_loopback() || c.tls_cert.is_some(),
            "non-loopback listeners require TLS; bind 127.0.0.1 and publish it with Tailscale Funnel/serve or an HTTPS reverse proxy instead"
        );
        if !c.public_url.is_empty() {
            root_url(&c.public_url).context("invalid public_url")?;
        }
        ensure!(
            !c.omp_args
                .iter()
                .any(
                    |a| ["--mode", "--no-session", "--session"].contains(&a.as_str())
                        || a.starts_with("--mode=")
                        || a.starts_with("--session=")
                ),
            "omp_args must not override mode or session storage"
        );
        for root in &mut c.workspaces {
            let s = root.to_string_lossy();
            if s == "~" {
                *root = dirs::home_dir().context("home unavailable")?;
            } else if s.starts_with("~/") || s.starts_with("~\\") {
                *root = dirs::home_dir().context("home unavailable")?.join(&s[2..]);
            }
        }
        Ok(c)
    }
}
