//! User-owned background service installation. Never depends on the npm installation tree.
use anyhow::{Context, Result, ensure};
use pinkcollab_gateway::{config::Config, storage};
use std::{
    path::{Path, PathBuf},
    process::{Command, Output},
};

#[cfg(any(target_os = "linux", test))]
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
mod linux;
#[cfg(any(target_os = "macos", test))]
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
mod macos;
#[cfg(windows)]
mod windows;
#[cfg(any(windows, test))]
mod windows_ux;

#[derive(Debug, PartialEq, Eq)]
pub enum ServiceStatus {
    NotInstalled,
    Stopped,
    Running,
    Failed(String),
}

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum InstallOutcome {
    Installed,
    Updated,
    Unchanged,
}
impl InstallOutcome {
    fn progress(self) {
        match self {
            Self::Installed => println!("Installing background Gateway..."),
            Self::Updated => println!("Updating background Gateway..."),
            Self::Unchanged => {}
        }
    }
    pub fn message(self) -> &'static str {
        match self {
            Self::Installed => "✓ Background Gateway installed",
            Self::Updated => "✓ Background Gateway updated",
            Self::Unchanged => "✓ Background Gateway ready",
        }
    }
}
/// Missing is distinct from an unreadable/corrupt installation.
fn read_optional(path: &Path) -> Result<Option<Vec<u8>>> {
    match std::fs::read(path) {
        Ok(bytes) => Ok(Some(bytes)),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error).with_context(|| format!("cannot read {}", path.display())),
    }
}
pub struct BinaryIdentity {
    pub installed: Option<String>,
    pub current: String,
}
impl BinaryIdentity {
    fn matches(&self) -> bool {
        self.installed.as_ref() == Some(&self.current)
    }
}

#[derive(clap::Subcommand)]
pub enum Action {
    Install,
    Uninstall,
    Start,
    Stop,
    Restart,
    Status,
}

pub trait ServiceManager {
    fn preflight(&self, _non_interactive: bool) -> Result<()> {
        Ok(())
    }

    fn install(&self) -> Result<InstallOutcome>;
    fn uninstall(&self) -> Result<()>;
    fn start(&self) -> Result<()>;
    fn stop(&self) -> Result<()>;
    fn status(&self) -> Result<ServiceStatus>;
    fn restart(&self) -> Result<()> {
        self.stop()?;
        self.start()
    }
}

pub trait Runner {
    fn run(&self, program: &str, args: &[&str]) -> Result<Output>;
}
pub struct SystemRunner;
impl Runner for SystemRunner {
    fn run(&self, program: &str, args: &[&str]) -> Result<Output> {
        pinkcollab_gateway::command::output(
            Command::new(program).args(args),
            std::time::Duration::from_secs(50),
        )
        .with_context(|| format!("cannot run {program}"))
    }
}
pub fn checked(runner: &dyn Runner, program: &str, args: &[&str]) -> Result<Output> {
    let output = runner.run(program, args)?;
    ensure!(
        output.status.success(),
        "{program} failed: {}",
        String::from_utf8_lossy(&output.stderr).trim()
    );
    Ok(output)
}

pub struct Installation {
    pub dir: PathBuf,
    pub binary: PathBuf,
    pub definition: PathBuf,
    pub path: String,
}
impl Installation {
    pub fn new(dir: &Path) -> Result<Self> {
        let home = dirs::home_dir().context("home directory unavailable")?;
        let dir = canonical_destination(dir)?;
        #[cfg(target_os = "macos")]
        let (base, definition) = (
            home.join("Library/Application Support/PinkCollab"),
            home.join("Library/LaunchAgents/dev.pinkcollab.gateway.plist"),
        );
        #[cfg(target_os = "linux")]
        let (base, definition) = (
            home.join(".local/share/pinkcollab"),
            home.join(".config/systemd/user/pinkcollab.service"),
        );
        #[cfg(windows)]
        let (base, definition) = {
            let base = PathBuf::from(
                std::env::var_os("LOCALAPPDATA").context("LOCALAPPDATA unavailable")?,
            )
            .join("PinkCollab");
            let startup =
                PathBuf::from(std::env::var_os("APPDATA").context("APPDATA unavailable")?)
                    .join("Microsoft/Windows/Start Menu/Programs/Startup/PinkCollab.vbs");
            (base, startup)
        };
        let mut paths: Vec<PathBuf> =
            std::env::split_paths(&std::env::var_os("PATH").unwrap_or_default())
                .filter(|p| p.is_absolute())
                .collect();
        for path in [
            home.join(".local/bin"),
            home.join(".bun/bin"),
            #[cfg(unix)]
            PathBuf::from("/opt/homebrew/bin"),
            #[cfg(unix)]
            PathBuf::from("/usr/local/bin"),
            #[cfg(unix)]
            PathBuf::from("/usr/bin"),
            #[cfg(unix)]
            PathBuf::from("/bin"),
        ] {
            if !paths.contains(&path) {
                paths.push(path);
            }
        }
        let path = std::env::join_paths(paths)?
            .into_string()
            .map_err(|_| anyhow::anyhow!("PATH must be UTF-8"))?;
        Ok(Self {
            dir,
            binary: base.join("bin").join(if cfg!(windows) {
                "pinkcollab-gateway.exe"
            } else {
                "pinkcollab-gateway"
            }),
            definition,
            path,
        })
    }
    /// The durable pending record survives any failure between staging and activation.
    fn update(&self, definition: &str, running: bool) -> Result<Update<'_>> {
        use sha2::{Digest, Sha256};
        let current_binary = std::fs::read(std::env::current_exe()?)?;
        let binary_changed = !self.identity_for(&current_binary)?.matches();
        let config = std::fs::read(self.dir.join("config.yaml"))?;
        let mut hash = Sha256::new();
        for bytes in [
            current_binary.as_slice(),
            config.as_slice(),
            definition.as_bytes(),
        ] {
            hash.update((bytes.len() as u64).to_le_bytes());
            hash.update(bytes);
        }
        let fingerprint = hex::encode(hash.finalize());
        let base = self.binary.parent().unwrap();
        let pending = base.join("update-pending");
        let resume = running || std::fs::read_to_string(&pending).is_ok_and(|v| v == "running");
        let changed = binary_changed
            || pending.exists()
            || std::fs::read_to_string(base.join("installed.sha256"))
                .map_or(true, |old| old != fingerprint);
        Ok(Update {
            installation: self,
            fingerprint,
            current_binary,
            binary_changed,
            changed,
            resume,
        })
    }
    pub fn binary_identity(&self) -> Result<BinaryIdentity> {
        self.identity_for(&std::fs::read(std::env::current_exe()?)?)
    }
    fn identity_for(&self, current: &[u8]) -> Result<BinaryIdentity> {
        use sha2::{Digest, Sha256};
        Ok(BinaryIdentity {
            installed: read_optional(&self.binary)?.map(|bytes| hex::encode(Sha256::digest(bytes))),
            current: hex::encode(Sha256::digest(current)),
        })
    }
    #[cfg(test)]
    fn stage_binary(&self) -> Result<bool> {
        let bytes = std::fs::read(std::env::current_exe()?)?;
        if self.identity_for(&bytes)?.matches() {
            return Ok(false);
        }
        self.write_binary(&bytes)?;
        Ok(true)
    }
    fn write_binary(&self, bytes: &[u8]) -> Result<()> {
        storage::private_dir(self.binary.parent().unwrap())?;
        let temporary = self.binary.with_extension("new");
        storage::replace_private_file(&temporary, bytes)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&temporary, std::fs::Permissions::from_mode(0o700))?;
        }
        std::fs::rename(&temporary, &self.binary)
            .context("cannot update stable Gateway binary; stop the service and retry")?;
        Ok(())
    }
    #[cfg(any(unix, test))]
    pub fn write_definition(&self, content: &str) -> Result<bool> {
        if std::fs::read_to_string(&self.definition).is_ok_and(|old| old == content) {
            return Ok(false);
        }
        // Do not chmod shared LaunchAgents/systemd directories.
        std::fs::create_dir_all(self.definition.parent().unwrap())?;
        storage::replace_private_file(&self.definition, content.as_bytes())?;
        Ok(true)
    }
    pub fn check_owner(&self) -> Result<()> {
        let owner = self.binary.parent().unwrap().join("data-dir");
        ensure!(
            owner.exists() || !self.definition.exists(),
            "an unmanaged service definition already exists; inspect it before replacing it with pinkcollab service install"
        );
        if owner.exists() {
            ensure!(
                std::fs::read_to_string(&owner)? == self.dir.to_string_lossy(),
                "a service is already installed for another data directory; uninstall it with that --data-dir first"
            );
        }
        Ok(())
    }
    pub fn record_owner(&self) -> Result<()> {
        storage::replace_private_file(
            &self.binary.parent().unwrap().join("data-dir"),
            self.dir.to_string_lossy().as_bytes(),
        )
    }
    pub fn forget_owner(&self) -> Result<()> {
        for name in ["installed.sha256", "update-pending", "data-dir"] {
            let path = self.binary.parent().unwrap().join(name);
            if path.exists() {
                std::fs::remove_file(path)?;
            }
        }
        Ok(())
    }
}

/// Resolve even a fresh destination without creating directories during preflight.
fn canonical_destination(path: &Path) -> Result<PathBuf> {
    if path.exists() {
        return Ok(path.canonicalize()?);
    }
    let absolute = std::path::absolute(path)?;
    let parent = absolute.parent().context("data directory has no parent")?;
    Ok(
        canonical_destination(parent)?
            .join(absolute.file_name().context("invalid data directory")?),
    )
}

struct Update<'a> {
    installation: &'a Installation,
    fingerprint: String,
    current_binary: Vec<u8>,
    binary_changed: bool,
    changed: bool,
    resume: bool,
}
impl Update<'_> {
    fn stage_binary(&self) -> Result<()> {
        if self.binary_changed {
            self.installation.write_binary(&self.current_binary)?;
        }
        Ok(())
    }
    fn begin(&self) -> Result<()> {
        let i = self.installation;
        storage::private_dir(i.binary.parent().unwrap())?;
        i.record_owner()?;
        storage::replace_private_file(
            &i.binary.parent().unwrap().join("update-pending"),
            if self.resume { b"running" } else { b"stopped" },
        )
    }
    fn commit(self) -> Result<()> {
        let base = self.installation.binary.parent().unwrap();
        storage::replace_private_file(&base.join("installed.sha256"), self.fingerprint.as_bytes())?;
        std::fs::remove_file(base.join("update-pending"))?;
        Ok(())
    }
}

#[cfg(test)]
fn windows_command(binary: &Path, dir: &Path) -> String {
    windows_arguments(binary, dir, "service-run")
}
#[cfg(any(windows, test))]
pub(crate) fn windows_arguments(binary: &Path, dir: &Path, action: &str) -> String {
    fn quote(path: &Path) -> String {
        let value = path.to_string_lossy();
        // Windows paths cannot contain quotes; terminal backslashes must be doubled.
        let trailing = value.chars().rev().take_while(|c| *c == '\\').count();
        format!("\"{}{}\"", value, "\\".repeat(trailing))
    }
    format!("{} --data-dir {} {action}", quote(binary), quote(dir))
}

pub fn manager(installation: &Installation) -> Box<dyn ServiceManager + '_> {
    #[cfg(target_os = "macos")]
    {
        Box::new(macos::Manager {
            installation,
            runner: &SystemRunner,
        })
    }
    #[cfg(target_os = "linux")]
    {
        Box::new(linux::Manager {
            installation,
            runner: &SystemRunner,
        })
    }
    #[cfg(windows)]
    {
        Box::new(windows::Manager { installation })
    }
}
pub fn execute(dir: &Path, action: Action) -> Result<()> {
    let installation = Installation::new(dir)?;
    installation.check_owner()?;
    let manager = manager(&installation);
    match action {
        Action::Install => {
            Config::load(dir)?;
            manager.preflight(false)?;
            manager.install()?;
        }
        Action::Uninstall => manager.uninstall()?,
        Action::Start => manager.start()?,
        Action::Stop => manager.stop()?,
        Action::Restart => manager.restart()?,
        Action::Status => {
            let state = manager.status()?;
            println!("Gateway service: {state:?}");
            ensure!(
                state == ServiceStatus::Running,
                "Gateway service needs attention"
            );
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn binary_identity_distinguishes_missing_from_io_failure() {
        let root = tempfile::tempdir().unwrap();
        let i = Installation {
            dir: root.path().join("data"),
            binary: root.path().join("binary"),
            definition: root.path().join("definition"),
            path: String::new(),
        };
        assert!(i.binary_identity().unwrap().installed.is_none());
        std::fs::create_dir(&i.binary).unwrap();
        assert!(i.binary_identity().is_err());
        assert!(i.stage_binary().is_err());
    }
    #[test]
    fn repeat_install_preserves_identical_files_and_rejects_other_data_dir() {
        let root = tempfile::tempdir().unwrap();
        let i = Installation {
            dir: root.path().join("data"),
            binary: root.path().join("bin/gateway"),
            definition: root.path().join("service"),
            path: "/bin".into(),
        };
        assert!(i.stage_binary().unwrap());
        assert!(!i.stage_binary().unwrap());
        assert!(i.binary_identity().unwrap().matches());
        std::fs::write(&i.binary, b"old build").unwrap();
        assert!(!i.binary_identity().unwrap().matches());
        assert!(i.write_definition("definition").unwrap());
        assert!(!i.write_definition("definition").unwrap());
        i.record_owner().unwrap();
        i.check_owner().unwrap();
        let other = Installation {
            dir: root.path().join("other"),
            ..i
        };
        assert!(other.check_owner().is_err());
    }
    #[test]
    fn update_keeps_activation_intent_after_files_have_been_staged() {
        let root = tempfile::tempdir().unwrap();
        let i = Installation {
            dir: root.path().join("data"),
            binary: root.path().join("bin/gateway"),
            definition: root.path().join("service"),
            path: "/bin".into(),
        };
        std::fs::create_dir_all(&i.dir).unwrap();
        std::fs::write(i.dir.join("config.yaml"), "config").unwrap();
        let first = i.update("definition", true).unwrap();
        first.begin().unwrap();
        i.stage_binary().unwrap();
        i.write_definition("definition").unwrap();
        // Simulate failure after stop, before activation. The next status is stopped.
        let retry = i.update("definition", false).unwrap();
        assert!(retry.changed && retry.resume);
        retry.begin().unwrap();
        retry.commit().unwrap();
        assert!(!i.update("definition", true).unwrap().changed);
        assert!(i.update("new environment", false).unwrap().changed);
    }
    #[test]
    fn fresh_destination_preflight_does_not_create_it() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("fresh/data");
        assert_eq!(
            canonical_destination(&path).unwrap(),
            root.path().canonicalize().unwrap().join("fresh/data")
        );
        assert!(!path.exists());
    }
    #[test]
    fn windows_service_command_preserves_spaces_and_root_backslash() {
        assert_eq!(
            windows_command(
                Path::new(r"C:\Program Files\Gateway.exe"),
                Path::new(r"C:\")
            ),
            "\"C:\\Program Files\\Gateway.exe\" --data-dir \"C:\\\\\" service-run"
        );
    }
}
