//! Apply the current CLI without running configuration or pairing setup.
use anyhow::{Context, Result, ensure};
use pinkcollab_gateway::config::Config;
use std::{
    io::{self, Write},
    path::Path,
};

use crate::service::{self, Activation, ServiceStatus};

pub async fn run(dir: &Path) -> Result<()> {
    if !dir.join("config.yaml").try_exists()? {
        anyhow::bail!("No PinkCollab configuration. Run pinkcollab setup.");
    }
    let config = Config::load(dir)
        .context("Your existing configuration needs attention. Run pinkcollab doctor.")?;
    let installation = service::Installation::new(dir)?;
    installation.check_owner()?;
    let manager = service::manager(&installation);
    let status = manager.status().context(
        "The background service needs attention. Run pinkcollab service status for details.",
    )?;
    ensure!(
        status != ServiceStatus::NotInstalled,
        "Background startup is not installed. Run pinkcollab service install, then pinkcollab service start."
    );
    let outcome = manager.install_with(Activation::Deferred)?;
    println!("{}", outcome.message());
    println!("Restarting stops active OMP runtimes. A stopped Gateway will start.");
    print!("Restart the Gateway service now? [y/N] ");
    io::stdout().flush()?;
    let mut answer = String::new();
    io::stdin().read_line(&mut answer)?;
    let answer = answer.trim();
    if !answer.eq_ignore_ascii_case("y") && !answer.eq_ignore_ascii_case("yes") {
        println!(
            "Skipped. To activate the installed Gateway later, run pinkcollab service restart."
        );
        return Ok(());
    }
    service::execute(dir, service::Action::Restart)?;
    crate::health::wait(config.listen).await?;
    println!("✓ Gateway running");
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
