use super::*;
const LABEL: &str = "dev.pinkcollab.gateway";
pub struct Manager<'a> {
    pub installation: &'a Installation,
    pub runner: &'a dyn Runner,
}
fn xml(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}
fn render(i: &Installation) -> String {
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>{LABEL}</string>
<key>ProgramArguments</key><array><string>{}</string><string>--data-dir</string><string>{}</string><string>serve</string></array>
<key>EnvironmentVariables</key><dict><key>PATH</key><string>{}</string></dict>
<key>RunAtLoad</key><true/>
<key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
<key>ExitTimeOut</key><integer>45</integer>
<key>Umask</key><integer>63</integer>
<key>StandardOutPath</key><string>{}</string>
<key>StandardErrorPath</key><string>{}</string>
</dict></plist>
"#,
        xml(&i.binary.to_string_lossy()),
        xml(&i.dir.to_string_lossy()),
        xml(&i.path),
        xml(&i.dir.join("logs/gateway.log").to_string_lossy()),
        xml(&i.dir.join("logs/gateway-error.log").to_string_lossy())
    )
}
impl Manager<'_> {
    fn domain(&self) -> Result<String> {
        let output = checked(self.runner, "id", &["-u"])?;
        Ok(format!(
            "gui/{}",
            String::from_utf8_lossy(&output.stdout).trim()
        ))
    }
    fn label(&self) -> Result<String> {
        Ok(format!("{}/{LABEL}", self.domain()?))
    }
    fn loaded(&self) -> Result<bool> {
        Ok(self
            .runner
            .run("launchctl", &["print", &self.label()?])?
            .status
            .success())
    }
}
impl ServiceManager for Manager<'_> {
    fn install(&self) -> Result<()> {
        let i = self.installation;
        i.check_owner()?;
        storage::private_dir(&i.dir.join("logs"))?;
        let definition = render(i);
        let mut update = i.update(&definition, false)?;
        if !update.changed {
            return Ok(());
        }
        update.resume |= self.loaded()?;
        update.begin()?;
        i.stage_binary()?;
        i.write_definition(&definition)?;
        if update.resume {
            self.stop()?;
            self.start()?;
        }
        update.commit()?;
        Ok(())
    }
    fn uninstall(&self) -> Result<()> {
        self.stop()?;
        if self.installation.definition.exists() {
            std::fs::remove_file(&self.installation.definition)?;
        }
        self.installation.forget_owner()
    }
    fn start(&self) -> Result<()> {
        if !self.loaded()? {
            checked(
                self.runner,
                "launchctl",
                &[
                    "bootstrap",
                    &self.domain()?,
                    &self.installation.definition.to_string_lossy(),
                ],
            )?;
        }
        if self.status()? != ServiceStatus::Running {
            checked(self.runner, "launchctl", &["kickstart", &self.label()?])?;
        }
        Ok(())
    }
    fn stop(&self) -> Result<()> {
        if self.loaded()? {
            checked(self.runner, "launchctl", &["bootout", &self.label()?])?;
        }
        Ok(())
    }
    fn status(&self) -> Result<ServiceStatus> {
        if !self.installation.definition.exists() {
            return Ok(ServiceStatus::NotInstalled);
        }
        let output = self.runner.run("launchctl", &["print", &self.label()?])?;
        if !output.status.success() {
            return Ok(ServiceStatus::Stopped);
        }
        let text = String::from_utf8_lossy(&output.stdout);
        if text.contains("state = running") {
            Ok(ServiceStatus::Running)
        } else if text
            .lines()
            .any(|l| l.trim().starts_with("last exit code =") && !l.trim().ends_with("= 0"))
        {
            Ok(ServiceStatus::Failed(
                "Gateway exited; inspect data-dir/logs".into(),
            ))
        } else {
            Ok(ServiceStatus::Stopped)
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn definition_escapes_paths_and_keeps_arguments_separate() {
        let i = Installation {
            dir: "/tmp/data & stuff".into(),
            binary: "/tmp/app bin/gateway".into(),
            definition: "unused".into(),
            path: "/a&b:/bin".into(),
        };
        let text = render(&i);
        assert!(text.contains("<string>/tmp/app bin/gateway</string>"));
        assert!(text.contains("<string>/tmp/data &amp; stuff</string>"));
        assert!(text.contains("/a&amp;b:/bin"));
        assert!(text.contains("<key>SuccessfulExit</key><false/>"));
    }
}

#[cfg(all(test, unix))]
mod manager_tests {
    use super::*;
    use std::{cell::RefCell, os::unix::process::ExitStatusExt};
    #[derive(Default)]
    struct Mock {
        calls: RefCell<Vec<String>>,
        fail_stop: std::cell::Cell<bool>,
    }
    impl Runner for Mock {
        fn run(&self, program: &str, args: &[&str]) -> Result<Output> {
            self.calls
                .borrow_mut()
                .push(format!("{program} {}", args.join(" ")));
            if args.first() == Some(&"bootout") && self.fail_stop.replace(false) {
                anyhow::bail!("injected bootout failure");
            }
            Ok(Output {
                status: std::process::ExitStatus::from_raw(0),
                stdout: if program == "id" {
                    b"501\n".to_vec()
                } else {
                    b"state = running\n".to_vec()
                },
                stderr: vec![],
            })
        }
    }
    #[test]
    fn identical_install_does_not_restart_or_invoke_launchctl() {
        let root = tempfile::tempdir().unwrap();
        let installation = Installation {
            dir: root.path().join("data"),
            binary: root.path().join("bin/gateway"),
            definition: root.path().join("agent.plist"),
            path: "/bin:/custom node".into(),
        };
        std::fs::create_dir_all(&installation.dir).unwrap();
        std::fs::write(installation.dir.join("config.yaml"), "config").unwrap();
        let runner = Mock::default();
        let manager = Manager {
            installation: &installation,
            runner: &runner,
        };
        manager.install().unwrap();
        assert!(runner.calls.borrow().iter().any(|c| c.contains("bootout")));
        runner.calls.borrow_mut().clear();
        manager.install().unwrap();
        assert!(runner.calls.borrow().is_empty());
        std::fs::write(installation.dir.join("config.yaml"), "changed config").unwrap();
        manager.install().unwrap();
        assert!(runner.calls.borrow().iter().any(|c| c.contains("bootout")));
        // Stage a binary update, then fail before the old process stops.
        std::fs::write(
            installation
                .binary
                .parent()
                .unwrap()
                .join("installed.sha256"),
            "old release",
        )
        .unwrap();
        std::fs::write(&installation.binary, "old binary").unwrap();
        runner.fail_stop.set(true);
        assert!(manager.install().is_err());
        runner.calls.borrow_mut().clear();
        manager.install().unwrap();
        assert!(runner.calls.borrow().iter().any(|c| c.contains("bootout")));
        assert_eq!(manager.status().unwrap(), ServiceStatus::Running);
    }
}
