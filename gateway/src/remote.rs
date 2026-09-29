//! Generic diagnostics only require Funnel when this installation configured it.
use pinkcollab_gateway::{
    config::{self, Config},
    funnel,
};
use std::path::Path;

#[derive(Debug)]
pub enum Access {
    Local,
    External,
    Funnel,
    Failed(String),
}
impl Access {
    pub fn failed(&self) -> bool {
        matches!(self, Self::Failed(_))
    }
    pub fn label(&self) -> &str {
        match self {
            Self::Local => "Local only",
            Self::External => "Externally managed (not verified)",
            Self::Funnel => "Funnel ready",
            Self::Failed(_) => "Needs attention",
        }
    }
}
pub fn inspect(dir: &Path, config: &Config) -> Access {
    if config.public_url.is_empty() {
        return Access::Local;
    }
    let marker = match std::fs::read_to_string(dir.join("funnel-url")) {
        Ok(marker) => marker,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Access::External,
        Err(error) => return Access::Failed(error.to_string()),
    };
    if marker != config.public_url {
        return Access::External;
    }
    let check = || -> anyhow::Result<()> {
        use anyhow::{Context, ensure};
        let binary = funnel::tailscale_binary().context("Tailscale binary unavailable")?;
        let state = funnel::state(&binary)?;
        let url = config::root_url(&config.public_url)?;
        let host = state.dns_name.context("Tailscale DNS unavailable")?;
        ensure!(
            state.backend_state == "Running"
                && url.scheme() == "https"
                && url.host_str() == Some(host.as_str()),
            "Tailscale connection or hostname changed"
        );
        ensure!(
            funnel::inspect(
                &binary,
                &host,
                url.port().unwrap_or(443),
                config.listen.port()
            )?,
            "Managed Funnel mapping is missing or changed"
        );
        Ok(())
    };
    match check() {
        Ok(()) => Access::Funnel,
        Err(error) => Access::Failed(format!("{error:#}")),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn unmanaged_deployments_do_not_require_tailscale() {
        let dir = tempfile::tempdir().unwrap();
        let mut config = Config::default();
        config.public_url.clear();
        assert!(matches!(inspect(dir.path(), &config), Access::Local));
        for url in ["https://proxy.example.com", "https://host.tailnet.ts.net"] {
            config.public_url = url.into();
            assert!(matches!(inspect(dir.path(), &config), Access::External));
        }
        std::fs::write(dir.path().join("funnel-url"), "https://old.ts.net").unwrap();
        assert!(matches!(inspect(dir.path(), &config), Access::External));
    }
}
