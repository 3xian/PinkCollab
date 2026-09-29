use super::*;
pub struct Manager<'a> {
    pub installation: &'a Installation,
    pub runner: &'a dyn Runner,
}
fn quote(value: &str) -> String {
    format!(
        "\"{}\"",
        value
            .replace('\\', "\\\\")
            .replace('"', "\\\"")
            .replace('%', "%%")
            .replace('$', "$$")
            .replace('\n', "\\n")
            .replace('\r', "\\r")
            .replace('\t', "\\t")
    )
}
fn render(i: &Installation) -> String {
    format!(
        "[Unit]\nDescription=PinkCollab Gateway\n[Service]\nExecStart={} --data-dir {} serve\nEnvironment={}\nRestart=on-failure\nTimeoutStopSec=45\nUMask=0077\n[Install]\nWantedBy=default.target\n",
        quote(&i.binary.to_string_lossy()),
        quote(&i.dir.to_string_lossy()),
        quote(&format!("PATH={}", i.path)).replace("$$", "$")
    )
}
impl ServiceManager for Manager<'_> {
    fn install(&self) -> Result<InstallOutcome> {
        let i = self.installation;
        let fresh = !i.definition.try_exists()?;
        i.check_owner()?;
        let running = self.status()? == ServiceStatus::Running;
        let definition = render(i);
        let update = i.update(&definition, running)?;
        if !update.changed
            && read_optional(&i.definition)?.as_deref() == Some(definition.as_bytes())
        {
            return Ok(InstallOutcome::Unchanged);
        }
        let outcome = if fresh {
            InstallOutcome::Installed
        } else {
            InstallOutcome::Updated
        };
        outcome.progress();
        update.begin()?;
        update.stage_binary()?;
        i.write_definition(&definition)?;
        checked(self.runner, "systemctl", &["--user", "daemon-reload"])?;
        checked(
            self.runner,
            "systemctl",
            &["--user", "enable", "pinkcollab"],
        )?;
        if update.resume {
            self.restart()?;
        }
        update.commit()?;
        Ok(outcome)
    }
    fn uninstall(&self) -> Result<()> {
        if self.installation.definition.exists() {
            checked(
                self.runner,
                "systemctl",
                &["--user", "disable", "--now", "pinkcollab"],
            )?;
            std::fs::remove_file(&self.installation.definition)?;
            checked(self.runner, "systemctl", &["--user", "daemon-reload"])?;
        }
        self.installation.forget_owner()
    }
    fn start(&self) -> Result<()> {
        checked(self.runner, "systemctl", &["--user", "start", "pinkcollab"])?;
        Ok(())
    }
    fn stop(&self) -> Result<()> {
        checked(self.runner, "systemctl", &["--user", "stop", "pinkcollab"])?;
        Ok(())
    }
    fn status(&self) -> Result<ServiceStatus> {
        if !self.installation.definition.exists() {
            return Ok(ServiceStatus::NotInstalled);
        }
        let output = checked(
            self.runner,
            "systemctl",
            &[
                "--user",
                "show",
                "pinkcollab",
                "--property=ActiveState",
                "--value",
            ],
        )?;
        Ok(match String::from_utf8_lossy(&output.stdout).trim() {
            "active" => ServiceStatus::Running,
            "failed" => ServiceStatus::Failed("inspect journalctl --user -u pinkcollab".into()),
            _ => ServiceStatus::Stopped,
        })
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn unit_quotes_paths_and_specifiers() {
        let i = Installation {
            dir: "/data a/%x".into(),
            binary: "/bin/a b".into(),
            definition: "unused".into(),
            path: "/a b:/bin".into(),
        };
        let text = render(&i);
        assert!(text.contains("ExecStart=\"/bin/a b\" --data-dir \"/data a/%%x\" serve"));
        assert!(text.contains("Environment=\"PATH=/a b:/bin\""));
        assert!(text.contains("UMask=0077"));
    }
}

#[cfg(all(test, unix))]
mod manager_tests {
    use super::*;
    use std::{cell::RefCell, os::unix::process::ExitStatusExt};
    #[derive(Default)]
    struct Mock {
        calls: RefCell<Vec<String>>,
    }
    impl Runner for Mock {
        fn run(&self, program: &str, args: &[&str]) -> Result<Output> {
            self.calls
                .borrow_mut()
                .push(format!("{program} {}", args.join(" ")));
            Ok(Output {
                status: std::process::ExitStatus::from_raw(0),
                stdout: b"active\n".to_vec(),
                stderr: vec![],
            })
        }
    }
    #[test]
    fn repeat_install_reloads_without_restarting_running_gateway() {
        let root = tempfile::tempdir().unwrap();
        let installation = Installation {
            dir: root.path().join("data"),
            binary: root.path().join("bin/gateway"),
            definition: root.path().join("unit.service"),
            path: "/bin".into(),
        };
        std::fs::create_dir_all(&installation.dir).unwrap();
        std::fs::write(installation.dir.join("config.yaml"), "config").unwrap();
        let runner = Mock::default();
        let manager = Manager {
            installation: &installation,
            runner: &runner,
        };
        assert_eq!(manager.install().unwrap(), InstallOutcome::Installed);
        runner.calls.borrow_mut().clear();
        assert_eq!(manager.install().unwrap(), InstallOutcome::Unchanged);
        assert!(
            runner
                .calls
                .borrow()
                .iter()
                .all(|c| !c.contains("stop") && !c.contains("start"))
        );
        std::fs::write(installation.dir.join("config.yaml"), "updated config").unwrap();
        assert_eq!(manager.install().unwrap(), InstallOutcome::Updated);
        std::fs::write(&installation.definition, "changed definition").unwrap();
        assert_eq!(manager.install().unwrap(), InstallOutcome::Updated);
        assert_eq!(manager.status().unwrap(), ServiceStatus::Running);
    }
}
