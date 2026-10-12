//! Apply the current CLI without running configuration or pairing setup.
use anyhow::{Context, Result, ensure};
use pinkcollab_gateway::config::Config;
use std::path::Path;

use crate::service::{self, InstallOutcome, ServiceStatus};

pub async fn run(dir: &Path) -> Result<()> {
    if !dir.join("config.yaml").try_exists()? {
        anyhow::bail!("No PinkCollab configuration. Run pinkcollab setup.");
    }
    let config = Config::load(dir)
        .context("Your existing configuration needs attention. Run pinkcollab doctor.")?;
    let installation = service::Installation::new(dir)?;
    installation.check_owner()?;
    let manager = service::manager(&installation);
    let before = manager.status().context(
        "The background service needs attention. Run pinkcollab service status for details.",
    )?;
    ensure!(
        before != ServiceStatus::NotInstalled,
        "Background startup is not installed. Run pinkcollab service install, then pinkcollab service start."
    );
    let report = manager.install()?;
    println!("{}", report.outcome.message());
    if report.resumed {
        if before == ServiceStatus::Running {
            println!(
                "Restarted the Gateway. Active OMP runtimes were stopped; stored sessions and pairings remain."
            );
        } else {
            println!("Started the Gateway.");
        }
        crate::health::wait(config.listen).await?;
        println!("✓ Gateway running");
    } else if report.outcome != InstallOutcome::Unchanged {
        println!(
            "Updated the installed binary without starting the Gateway. Run pinkcollab service start to use it."
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn update_without_configuration_points_to_setup() {
        let dir = tempfile::tempdir().unwrap();
        let error = run(dir.path()).await.unwrap_err();
        assert!(error.to_string().contains("pinkcollab setup"), "{error:#}");
    }
}
