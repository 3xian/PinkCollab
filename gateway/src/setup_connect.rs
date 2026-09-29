//! Bounded, cancellable Tailscale sign-in. Probe children are reaped on cancellation.
use crate::setup_control::Control;
use anyhow::{Context, Result, ensure};
use std::{path::Path, time::Duration};
use tokio::process::Command;

const TIMEOUT: Duration = Duration::from_secs(120);
const SIGN_IN_HELP: &str = if cfg!(target_os = "macos") {
    "For the recommended Homebrew CLI, run `sudo brew services start tailscale`, then `tailscale up` and complete sign-in. If you use Tailscale.app, open it and finish signing in."
} else {
    "Open the Tailscale app and finish signing in."
};
fn timeout_help() -> String {
    format!(
        "Tailscale still isn't connected.\n\n{SIGN_IN_HELP}\n\nThen run:\n\n  pinkcollab setup\n\nFor diagnostics:\n\n  pinkcollab doctor"
    )
}

#[derive(Debug, PartialEq)]
enum State {
    Connected,
    NeedsLogin,
    Starting,
    Stopped,
    Unavailable,
}
impl State {
    fn parse(value: &str) -> Self {
        match value {
            "Running" => Self::Connected,
            "NeedsLogin" => Self::NeedsLogin,
            "Starting" | "NoState" => Self::Starting,
            "Stopped" => Self::Stopped,
            _ => Self::Unavailable,
        }
    }
    fn message(&self) -> &str {
        match self {
            Self::Connected => "Tailscale connected.",
            Self::NeedsLogin => "Tailscale sign-in required. Waiting for Tailscale...",
            Self::Starting => "Tailscale is starting...",
            Self::Stopped => "Tailscale is installed but not connected. Waiting for Tailscale...",
            Self::Unavailable => "Waiting for Tailscale to respond...",
        }
    }
}
async fn probe(binary: &Path) -> State {
    // wait_with_output owns the kill-on-drop child, including when this future is cancelled.
    let output = Command::new(binary)
        .args(["status", "--json"])
        .kill_on_drop(true)
        .output();
    match tokio::time::timeout(Duration::from_secs(10), output).await {
        Ok(Ok(output)) => serde_json::from_slice::<serde_json::Value>(&output.stdout)
            .ok()
            .and_then(|v| v["BackendState"].as_str().map(State::parse))
            .unwrap_or(State::Unavailable),
        _ => State::Unavailable,
    }
}
pub async fn connect(control: &Control, binary: &Path, non_interactive: bool) -> Result<()> {
    connect_with_timeout(
        control,
        binary,
        non_interactive,
        TIMEOUT,
        Duration::from_secs(2),
    )
    .await
}
async fn connect_with_timeout(
    control: &Control,
    binary: &Path,
    non_interactive: bool,
    timeout: Duration,
    interval: Duration,
) -> Result<()> {
    let mut child = None;
    let work = async {
        let mut state = probe(binary).await;
        if state == State::Connected {
            return Ok(());
        }
        ensure!(
            !non_interactive,
            "Tailscale isn't connected. Sign in to Tailscale before running non-interactive setup.\n\n{SIGN_IN_HELP}"
        );
        println!("{}", state.message());
        child = Some(
            Command::new(binary)
                .arg("up")
                .kill_on_drop(true)
                .spawn()
                .with_context(|| format!("{SIGN_IN_HELP} Then run pinkcollab setup."))?,
        );
        let mut exited = false;
        let mut reminder = tokio::time::Instant::now();
        loop {
            tokio::time::sleep(interval).await;
            if !exited && let Some(status) = child.as_mut().unwrap().try_wait()? {
                exited = true;
                if status.success() {
                    println!(
                        "Tailscale command completed, but the connection is not ready yet.\nWaiting for Tailscale..."
                    );
                } else {
                    println!("Tailscale needs attention.\n\n{SIGN_IN_HELP}");
                }
            }
            let next = probe(binary).await;
            if next == State::Connected {
                return Ok(());
            }
            if next != state {
                println!("{}", next.message());
                state = next;
            }
            if reminder.elapsed() >= Duration::from_secs(30) {
                println!("Still waiting for Tailscale sign-in... (Ctrl+C to cancel)");
                reminder = tokio::time::Instant::now();
            }
        }
    };
    let result = control
        .wait(async {
            tokio::time::timeout(timeout, work)
                .await
                .unwrap_or_else(|_| Err(anyhow::anyhow!(timeout_help())))
        })
        .await;
    if let Some(mut child) = child {
        let _ = child.kill().await;
        let _ = child.wait().await;
    }
    result
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    async fn scenario(initial: &str, later: &str, exit: i32, success: bool) {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let binary = dir.path().join("tailscale");
        std::fs::write(&binary, format!("#!/bin/sh\ncd -- \"$(dirname -- \"$0\")\"\nif [ \"$1\" = up ]; then touch called; exit {exit}; fi\nif [ -f called ]; then echo '{{\"BackendState\":\"{later}\"}}'; else echo '{{\"BackendState\":\"{initial}\"}}'; fi\n")).unwrap();
        std::fs::set_permissions(&binary, std::fs::Permissions::from_mode(0o700)).unwrap();
        let result = connect_with_timeout(
            &Control::new().unwrap(),
            &binary,
            false,
            Duration::from_secs(3),
            Duration::from_millis(10),
        )
        .await;
        assert_eq!(result.is_ok(), success);
        assert_eq!(dir.path().join("called").exists(), initial != "Running");
        if let Err(error) = result {
            assert!(error.to_string().contains("pinkcollab setup"));
        }
    }
    #[tokio::test]
    async fn stalled_status_probe_obeys_overall_deadline() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let binary = dir.path().join("tailscale");
        std::fs::write(&binary, "#!/bin/sh\nexec /bin/sleep 30\n").unwrap();
        std::fs::set_permissions(&binary, std::fs::Permissions::from_mode(0o700)).unwrap();
        let start = std::time::Instant::now();
        let error = connect_with_timeout(
            &Control::new().unwrap(),
            &binary,
            false,
            Duration::from_millis(100),
            Duration::from_millis(10),
        )
        .await
        .unwrap_err();
        assert!(error.to_string().contains("pinkcollab doctor"));
        assert!(start.elapsed() < Duration::from_secs(2));
    }
    #[tokio::test]
    async fn cancellation_reaps_the_login_child() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let binary = dir.path().join("tailscale");
        std::fs::write(&binary, "#!/bin/sh\nif [ \"$1\" = status ]; then echo '{\"BackendState\":\"NeedsLogin\"}'; else echo $$ > \"$0.pid\"; exec /bin/sleep 30; fi\n").unwrap();
        std::fs::set_permissions(&binary, std::fs::Permissions::from_mode(0o700)).unwrap();
        let pid_file = binary.with_extension("pid");
        let watch = pid_file.clone();
        let control = Control::from_signal(async move {
            while !watch.exists() {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        });
        let error = connect_with_timeout(
            &control,
            &binary,
            false,
            Duration::from_secs(5),
            Duration::from_millis(10),
        )
        .await
        .unwrap_err();
        assert!(error.to_string().contains("cancelled"));
        let pid: i32 = std::fs::read_to_string(pid_file)
            .unwrap()
            .trim()
            .parse()
            .unwrap();
        assert_eq!(unsafe { libc::kill(pid, 0) }, -1);
        assert_eq!(
            std::io::Error::last_os_error().raw_os_error(),
            Some(libc::ESRCH)
        );
    }
    #[tokio::test]
    async fn already_connected() {
        scenario("Running", "Running", 0, true).await;
    }
    #[tokio::test]
    async fn login_completes() {
        scenario("NeedsLogin", "Running", 0, true).await;
    }
    #[tokio::test]
    async fn successful_command_is_not_connection() {
        scenario("Stopped", "Stopped", 0, false).await;
    }
    #[tokio::test]
    async fn failed_command_can_recover() {
        scenario("NeedsLogin", "Running", 1, true).await;
    }
    #[tokio::test]
    async fn never_connected() {
        scenario("NoState", "Starting", 1, false).await;
    }
}
