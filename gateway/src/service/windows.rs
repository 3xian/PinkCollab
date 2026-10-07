//! Per-user login startup with ownership-checked management of older SCM installs.
use super::*;
use std::time::{Duration, Instant};
use windows_service::{
    service::{ServiceAccess, ServiceState},
    service_manager::{ServiceManager as Scm, ServiceManagerAccess},
};
pub type LegacyLookup =
    fn(&Installation, ServiceAccess) -> Result<Option<windows_service::service::Service>>;

pub struct Manager<'a> {
    pub installation: &'a Installation,
    pub legacy_lookup: LegacyLookup,
}

enum Backend {
    Desktop,
    Legacy(windows_service::service::Service),
    Missing,
}

fn environment(path: &str) -> Result<String> {
    let values = ["HOME", "APPDATA", "LOCALAPPDATA"]
        .into_iter()
        .filter_map(|key| std::env::var(key).ok().map(|value| (key.into(), value)));
    windows_ux::serialize_environment(
        path,
        &dirs::home_dir()
            .context("user profile unavailable")?
            .to_string_lossy(),
        values,
    )
}

impl Manager<'_> {
    fn backend(&self, access: ServiceAccess) -> Result<Backend> {
        let i = self.installation;
        if crate::windows::is_running(&i.dir)? || i.definition.exists() {
            return Ok(Backend::Desktop);
        }
        Ok(match self.legacy(access)? {
            Some(service) => Backend::Legacy(service),
            None => Backend::Missing,
        })
    }
    fn definition(&self) -> String {
        windows_ux::startup_script(&windows_arguments(
            &self.installation.binary,
            &self.installation.dir,
            "background-start",
        ))
    }
    fn legacy(&self, access: ServiceAccess) -> Result<Option<windows_service::service::Service>> {
        (self.legacy_lookup)(self.installation, access)
    }
    fn migrate(&self) -> Result<()> {
        if let Some(service) = self.legacy(ServiceAccess::STOP | ServiceAccess::DELETE)? {
            println!("Migrating the old Windows service to login startup...");
            stop_legacy(&service)?;
            service.delete()?;
        }
        Ok(())
    }
}

pub fn system_legacy(
    installation: &Installation,
    access: ServiceAccess,
) -> Result<Option<windows_service::service::Service>> {
    let result = Scm::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)?.open_service(
        "PinkCollab",
        ServiceAccess::QUERY_STATUS | ServiceAccess::QUERY_CONFIG | access,
    );
    match result {
        Ok(service) => {
            let config = service.query_config()?;
            let identity = checked(&SystemRunner, "whoami", &[])?;
            let computer = checked(&SystemRunner, "hostname", &[])?;
            windows_ux::local_account(
                &config.account_name.context("legacy service account missing")?.to_string_lossy(),
                String::from_utf8_lossy(&identity.stdout).trim(),
                String::from_utf8_lossy(&computer.stdout).trim(),
            )?;
            windows_ux::legacy_command(
                config.executable_path.as_os_str(),
                &installation.binary,
                &installation.dir,
            )?;
            Ok(Some(service))
        }
        Err(windows_service::Error::Winapi(error)) if error.raw_os_error() == Some(1060) => Ok(None),
        Err(error) => Err(error).context("Cannot migrate the old Windows service. Run pinkcollab service install once in an Administrator terminal as the same user. Future installs need no elevation or password."),
    }
}

fn stop_legacy(service: &windows_service::service::Service) -> Result<()> {
    match service.query_status()?.current_state {
        ServiceState::Stopped => return Ok(()),
        ServiceState::StopPending => {}
        _ => {
            service.stop()?;
        }
    }
    let deadline = Instant::now() + Duration::from_secs(45);
    while service.query_status()?.current_state != ServiceState::Stopped {
        ensure!(
            Instant::now() < deadline,
            "Old Gateway has not stopped; retry after its active work exits"
        );
        std::thread::sleep(Duration::from_millis(100));
    }
    Ok(())
}

impl ServiceManager for Manager<'_> {
    fn preflight(&self, _non_interactive: bool) -> Result<()> {
        self.installation.check_owner()?;
        self.legacy(ServiceAccess::STOP | ServiceAccess::DELETE)?;
        println!("Windows background Gateway: starts at user login; no password required.");
        Ok(())
    }
    fn install(&self) -> Result<InstallOutcome> {
        let i = self.installation;
        self.preflight(false)?;
        let state = self.status()?;
        let environment = environment(&i.path)?;
        let definition = self.definition();
        let update = i.update(
            &format!("{definition}\n{environment}"),
            state == ServiceStatus::Running,
        )?;
        let definition_bytes = windows_ux::startup_bytes(&definition);
        let definition_same =
            read_optional(&i.definition)?.is_some_and(|bytes| bytes == definition_bytes);
        let environment_same = read_optional(&i.dir.join("service-environment.json"))?
            .is_some_and(|bytes| bytes == environment.as_bytes());
        if !update.changed
            && definition_same
            && environment_same
            && self.legacy(ServiceAccess::empty())?.is_none()
        {
            return Ok(InstallOutcome::Unchanged);
        }
        let outcome = if state == ServiceStatus::NotInstalled {
            InstallOutcome::Installed
        } else {
            InstallOutcome::Updated
        };
        outcome.progress();
        update.begin()?;
        self.migrate()?;
        self.stop()?;
        update.stage_binary()?;
        storage::replace_private_file(
            &i.dir.join("service-environment.json"),
            environment.as_bytes(),
        )?;
        storage::private_dir(i.definition.parent().unwrap())?;
        if !definition_same {
            storage::replace_private_file(&i.definition, &definition_bytes)?;
        }
        if update.resume {
            self.start()?;
        }
        update.commit()?;
        Ok(outcome)
    }
    fn uninstall(&self) -> Result<()> {
        self.installation.check_owner()?;
        self.migrate()?;
        self.stop()?;
        if self.installation.definition.exists() {
            std::fs::remove_file(&self.installation.definition)?;
        }
        self.installation.forget_owner()
    }
    fn start(&self) -> Result<()> {
        let i = self.installation;
        i.check_owner()?;
        if crate::windows::is_ready(&i.dir)? {
            return Ok(());
        }
        ensure!(
            i.definition.exists(),
            "Login startup is not installed; run pinkcollab setup or pinkcollab service install."
        );
        storage::private_dir(&i.dir)?;
        let child = if crate::windows::is_running(&i.dir)? {
            None
        } else {
            Some(crate::windows::spawn(&i.binary, &i.dir)?)
        };
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            if crate::windows::is_ready(&i.dir)? {
                return Ok(());
            }
            if let Some(child) = &child
                && let Some(status) = crate::windows::exit_code(child)?
            {
                ensure!(
                    status == 0,
                    "Background Gateway exited ({status}); inspect {}",
                    i.dir.join("gateway.log").display()
                );
            }
            ensure!(
                Instant::now() < deadline,
                "Gateway startup timed out; inspect {}",
                i.dir.join("gateway.log").display()
            );
            std::thread::sleep(Duration::from_millis(50));
        }
    }
    fn stop(&self) -> Result<()> {
        self.installation.check_owner()?;
        match self.backend(ServiceAccess::STOP)? {
            Backend::Desktop => crate::windows::stop(&self.installation.dir),
            Backend::Legacy(service) => stop_legacy(&service),
            Backend::Missing => Ok(()),
        }
    }
    fn status(&self) -> Result<ServiceStatus> {
        Ok(match self.backend(ServiceAccess::empty())? {
            Backend::Desktop => {
                if crate::windows::is_ready(&self.installation.dir)? {
                    ServiceStatus::Running
                } else {
                    ServiceStatus::Stopped
                }
            }
            Backend::Legacy(service) => {
                if service.query_status()?.current_state == ServiceState::Running {
                    ServiceStatus::Running
                } else {
                    ServiceStatus::Stopped
                }
            }
            Backend::Missing => ServiceStatus::NotInstalled,
        })
    }
}
