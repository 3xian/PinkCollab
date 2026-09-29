use super::*;
use windows_service::{
    service::{ServiceAccess, ServiceStartType, ServiceState},
    service_manager::{ServiceManager as Scm, ServiceManagerAccess},
};
pub struct Manager<'a> {
    pub installation: &'a Installation,
}
impl Manager<'_> {
    fn open(&self) -> Result<windows_service::service::Service> {
        self.open_with(ServiceAccess::empty())
    }
    fn open_with(&self, access: ServiceAccess) -> Result<windows_service::service::Service> {
        Scm::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)?
            .open_service(
                "PinkCollab",
                ServiceAccess::QUERY_STATUS | ServiceAccess::QUERY_CONFIG | access,
            )
            .context("open PinkCollab service (administrator permission may be required)")
    }
    fn verify_account(&self, service: &windows_service::service::Service) -> Result<()> {
        let account = service
            .query_config()?
            .account_name
            .context("service account is missing")?;
        let identity = checked(&SystemRunner, "whoami", &[])?;
        ensure!(
            account
                .to_string_lossy()
                .eq_ignore_ascii_case(String::from_utf8_lossy(&identity.stdout).trim()),
            "PinkCollab service must run as the current user; correct its Log On account in Windows Services"
        );
        Ok(())
    }
}
fn environment(path: &str) -> Result<String> {
    let mut values = std::collections::BTreeMap::new();
    values.insert("PATH".to_owned(), path.to_owned());
    for key in ["USERPROFILE", "HOME", "APPDATA", "LOCALAPPDATA"] {
        if let Ok(value) = std::env::var(key) {
            values.insert(key.to_owned(), value);
        }
    }
    values.insert(
        "USERPROFILE".to_owned(),
        dirs::home_dir()
            .context("user profile unavailable")?
            .to_string_lossy()
            .into_owned(),
    );
    Ok(serde_json::to_string(&values)?)
}
impl ServiceManager for Manager<'_> {
    fn preflight(&self, non_interactive: bool) -> Result<()> {
        if self.status()? == ServiceStatus::NotInstalled {
            ensure!(
                !non_interactive,
                "Install the Windows service interactively first: pinkcollab service install"
            );
        } else {
            self.verify_account(&self.open()?)?;
        }
        Ok(())
    }

    fn install(&self) -> Result<()> {
        use std::io::IsTerminal;
        let i = self.installation;
        i.check_owner()?;
        let environment = environment(&i.path)?;
        let state = self.status()?;
        let running = state == ServiceStatus::Running;
        let update = i.update(
            &format!("{}\n{environment}", windows_command(&i.binary, &i.dir)),
            running,
        )?;
        if state != ServiceStatus::NotInstalled {
            let service = self.open()?;
            self.verify_account(&service)?;
            let configuration = service.query_config()?;
            ensure!(
                configuration.start_type == ServiceStartType::AutoStart,
                "set PinkCollab service startup type to Automatic in Windows Services"
            );
            let binary_same = std::fs::read(std::env::current_exe()?)?
                == std::fs::read(&i.binary).unwrap_or_default();
            let path_same = std::fs::read_to_string(i.dir.join("service-environment.json"))
                .is_ok_and(|p| p == environment);
            let expected = windows_command(&i.binary, &i.dir);
            if binary_same
                && path_same
                && !update.changed
                && configuration.executable_path.to_string_lossy() == expected
            {
                return Ok(());
            }
        } else {
            ensure!(
                std::io::stdin().is_terminal(),
                "Windows service installation needs an administrator terminal and the current user's service login credentials. Run pinkcollab service install interactively first."
            );
        }
        update.begin()?;
        if state != ServiceStatus::NotInstalled {
            self.stop()?;
        }
        i.stage_binary()?;
        storage::replace_private_file(
            &i.dir.join("service-environment.json"),
            environment.as_bytes(),
        )?;
        // Credentials stay inside PowerShell's PSCredential and are never command arguments or logs.
        // New-Service receives an explicit user credential; it can never default to LocalSystem.
        let script = r#"$ErrorActionPreference='Stop'
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
$existing=Get-Service -Name PinkCollab -ErrorAction SilentlyContinue
if (-not $existing) {
  $credential=Get-Credential -UserName $identity.Name -Message 'PinkCollab must run as your account. Windows requires service login credentials.'
  if (-not $credential) { throw 'Service installation cancelled' }
  $sid=(New-Object Security.Principal.NTAccount($credential.UserName)).Translate([Security.Principal.SecurityIdentifier]).Value
  if ($sid -ne $identity.User.Value) { throw 'Choose the current Windows account' }
  New-Service -Name PinkCollab -DisplayName 'PinkCollab Gateway' -BinaryPathName $env:PINKCOLLAB_SERVICE_COMMAND -StartupType Automatic -Credential $credential | Out-Null
} else {
  sc.exe config PinkCollab binPath= $env:PINKCOLLAB_SERVICE_COMMAND | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Cannot update service; use an administrator terminal' }
}
"#;
        let command = windows_command(&i.binary, &i.dir);
        let status = Command::new("powershell.exe")
            .args(["-NoProfile", "-Command", script])
            .env("PINKCOLLAB_SERVICE_COMMAND", command)
            .status()?;
        ensure!(
            status.success(),
            "Windows could not install the service. Use an administrator terminal as the same user, and grant that account Log on as a service in Local Security Policy."
        );
        self.verify_account(&self.open()?)?;
        if update.resume {
            self.start()?;
        }
        update.commit()?;
        Ok(())
    }
    fn uninstall(&self) -> Result<()> {
        if self.status()? == ServiceStatus::NotInstalled {
            return self.installation.forget_owner();
        }
        self.stop()?;
        let service = self.open_with(ServiceAccess::DELETE)?;
        self.verify_account(&service)?;
        service.delete()?;
        self.installation.forget_owner()
    }
    fn start(&self) -> Result<()> {
        let service = self.open_with(ServiceAccess::START)?;
        self.verify_account(&service)?;
        if service.query_status()?.current_state != ServiceState::Running {
            service.start::<&str>(&[])?;
        }
        Ok(())
    }
    fn stop(&self) -> Result<()> {
        if self.status()? == ServiceStatus::NotInstalled {
            return Ok(());
        }
        let service = self.open_with(ServiceAccess::STOP)?;
        self.verify_account(&service)?;
        if service.query_status()?.current_state == ServiceState::Stopped {
            return Ok(());
        }
        service.stop()?;
        for _ in 0..180 {
            if service.query_status()?.current_state == ServiceState::Stopped {
                return Ok(());
            }
            std::thread::sleep(std::time::Duration::from_millis(250));
        }
        anyhow::bail!("Gateway has not stopped; retry after its active work exits")
    }
    fn status(&self) -> Result<ServiceStatus> {
        match self.open() {
            Ok(service) => {
                self.verify_account(&service)?;
                Ok(
                    if service.query_status()?.current_state == ServiceState::Running {
                        ServiceStatus::Running
                    } else if service.query_status()?.exit_code
                        != windows_service::service::ServiceExitCode::Win32(0)
                    {
                        ServiceStatus::Failed(
                            "Gateway exited with an error; inspect Windows Event Viewer".into(),
                        )
                    } else {
                        ServiceStatus::Stopped
                    },
                )
            }
            Err(error) => {
                if matches!(error.downcast_ref::<windows_service::Error>(), Some(windows_service::Error::Winapi(e)) if e.raw_os_error() == Some(1060))
                {
                    Ok(ServiceStatus::NotInstalled)
                } else {
                    Err(error)
                }
            }
        }
    }
}
