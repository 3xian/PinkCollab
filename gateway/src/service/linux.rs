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
    fn install_with(&self, activation: Activation) -> Result<InstallOutcome> {
        let i = self.installation;
        let fresh = !i.definition.try_exists()?;
        i.check_owner()?;
        let running =
            activation == Activation::Automatic && self.status()? == ServiceStatus::Running;
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
        if activation == Activation::Automatic && update.resume {
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

#[cfg(test)]
mod manager_tests {
    use super::*;
    use std::cell::RefCell;
    #[derive(Default)]
    struct Mock {
        calls: RefCell<Vec<String>>,
        stopped: std::cell::Cell<bool>,
    }
    impl Mock {
        fn assert_activation(&self, expected: &[&str]) {
            let calls = self.calls.take();
            let actions: Vec<_> = calls
                .iter()
                .filter_map(|call| call.strip_prefix("systemctl --user "))
                .filter(|action| matches!(*action, "stop pinkcollab" | "start pinkcollab"))
                .collect();
            assert_eq!(actions, expected);
        }
    }
    impl Runner for Mock {
        fn run(&self, program: &str, args: &[&str]) -> Result<Output> {
            self.calls
                .borrow_mut()
                .push(format!("{program} {}", args.join(" ")));
            Ok(Output {
                status: std::process::ExitStatus::default(),
                stdout: if self.stopped.get() {
                    b"inactive\n".to_vec()
                } else {
                    b"active\n".to_vec()
                },
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
        runner.assert_activation(&[]);
        assert_eq!(manager.install().unwrap(), InstallOutcome::Unchanged);
        runner.assert_activation(&[]);
        std::fs::write(installation.dir.join("config.yaml"), "updated config").unwrap();
        assert_eq!(manager.install().unwrap(), InstallOutcome::Updated);
        runner.assert_activation(&["stop pinkcollab", "start pinkcollab"]);
        std::fs::write(&installation.definition, "changed definition").unwrap();
        let outcome = manager.install_with(Activation::Deferred).unwrap();
        assert_eq!(outcome, InstallOutcome::Updated);
        runner.assert_activation(&[]);
        assert_eq!(manager.status().unwrap(), ServiceStatus::Running);
    }
    #[test]
    fn stopped_update_only_resumes_with_pending_activation_intent() {
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
        runner.stopped.set(true);
        let manager = Manager {
            installation: &installation,
            runner: &runner,
        };
        manager.install().unwrap();
        std::fs::write(installation.dir.join("config.yaml"), "changed config").unwrap();
        assert_eq!(manager.install().unwrap(), InstallOutcome::Updated);
        runner.assert_activation(&[]);
        std::fs::write(
            installation.binary.parent().unwrap().join("update-pending"),
            "running",
        )
        .unwrap();
        assert_eq!(manager.install().unwrap(), InstallOutcome::Updated);
        runner.assert_activation(&["stop pinkcollab", "start pinkcollab"]);
        assert_eq!(manager.status().unwrap(), ServiceStatus::Stopped);
    }
}
