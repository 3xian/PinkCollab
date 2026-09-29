//! One synchronously registered, sticky cancellation source for guided setup.
use anyhow::{Context, Result};
use futures_util::{
    FutureExt,
    future::{BoxFuture, Shared},
};

#[derive(Clone, Copy)]
pub enum Phase {
    BeforeToken,
    Installed,
    Running,
}
impl Phase {
    fn message(self) -> &'static str {
        match self {
            Self::BeforeToken => "Setup cancelled.\nNo pairing token was created.",
            Self::Installed => {
                "Setup cancelled. The background service remains installed.\nCheck pinkcollab status; pair later with pinkcollab pair."
            }
            Self::Running => {
                "Setup cancelled. Gateway remains running.\n\nPair later with:\n\n  pinkcollab pair"
            }
        }
    }
}
pub struct Control {
    signal: Shared<BoxFuture<'static, ()>>,
    pub phase: Phase,
}
impl Control {
    pub fn new() -> Result<Self> {
        #[cfg(unix)]
        let mut signal = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::interrupt())?;
        #[cfg(windows)]
        let mut signal = tokio::signal::windows::ctrl_c()?;
        Ok(Self::from_signal(async move {
            signal.recv().await;
        }))
    }
    pub(crate) fn from_signal(signal: impl Future<Output = ()> + Send + 'static) -> Self {
        Self {
            signal: signal.boxed().shared(),
            phase: Phase::BeforeToken,
        }
    }
    pub async fn wait<T>(&self, work: impl Future<Output = Result<T>>) -> Result<T> {
        tokio::select! {
            biased;
            _ = self.signal.clone() => anyhow::bail!(self.phase.message()),
            result = work => result,
        }
    }
    pub async fn checkpoint(&self) -> Result<()> {
        self.wait(async { Ok(()) }).await
    }
    /// Never abandon an in-flight mutation. Report cancellation only after it finishes.
    pub async fn blocking<T: Send + 'static>(
        &mut self,
        work: impl FnOnce() -> Result<T> + Send + 'static,
        completed: Option<Phase>,
    ) -> Result<T> {
        self.checkpoint().await?;
        let mut task = tokio::task::spawn_blocking(work);
        let result = tokio::select! {
            biased;
            _ = self.signal.clone() => (&mut task).await,
            result = &mut task => result,
        }
        .context("setup worker stopped")?;
        if result.is_ok()
            && let Some(phase) = completed
        {
            self.phase = phase;
        }
        // Preserve a failed mutation's diagnostic even if cancellation also arrived.
        let value = result?;
        self.checkpoint().await?;
        Ok(value)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(unix)]
    #[test]
    fn signal_registration_precedes_first_poll() {
        let result = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "setup_control::tests::signal_child",
                "--nocapture",
            ])
            .env("PINKCOLLAB_SIGNAL_TEST_CHILD", "1")
            .output()
            .unwrap();
        assert!(
            result.status.success(),
            "{}",
            String::from_utf8_lossy(&result.stderr)
        );
    }
    #[cfg(unix)]
    #[tokio::test]
    async fn signal_child() {
        if std::env::var_os("PINKCOLLAB_SIGNAL_TEST_CHILD").is_none() {
            return;
        }
        let control = Control::new().unwrap();
        // Deliver before the signal future has ever been polled, in an isolated process.
        unsafe {
            libc::raise(libc::SIGINT);
        }
        let error = tokio::time::timeout(
            std::time::Duration::from_secs(2),
            control.wait(std::future::pending::<Result<()>>()),
        )
        .await
        .unwrap()
        .unwrap_err();
        assert!(error.to_string().contains("No pairing token"));
    }
    #[tokio::test]
    async fn cancellation_is_sticky_and_prevents_work() {
        let (send, receive) = tokio::sync::oneshot::channel();
        let mut control = Control::from_signal(async {
            let _ = receive.await;
        });
        send.send(()).unwrap();
        assert!(
            control
                .checkpoint()
                .await
                .unwrap_err()
                .to_string()
                .contains("No pairing token")
        );
        assert!(
            control
                .blocking(|| -> Result<()> { panic!("must not start") }, None)
                .await
                .is_err()
        );
        assert!(control.wait(async { Ok(()) }).await.is_err());
    }
    #[tokio::test]
    async fn cancellation_drains_mutation_and_reports_completed_phase() {
        let (cancel, receive) = tokio::sync::oneshot::channel();
        let mut control = Control::from_signal(async {
            let _ = receive.await;
        });
        let root = tempfile::tempdir().unwrap();
        let marker = root.path().join("completed");
        let path = marker.clone();
        let error = control
            .blocking(
                move || {
                    cancel.send(()).unwrap();
                    std::thread::sleep(std::time::Duration::from_millis(30));
                    std::fs::write(path, "done")?;
                    Ok(())
                },
                Some(Phase::Installed),
            )
            .await
            .unwrap_err();
        assert!(marker.exists());
        assert!(error.to_string().contains("remains installed"));
        control.phase = Phase::Running;
        assert!(
            control
                .checkpoint()
                .await
                .unwrap_err()
                .to_string()
                .contains("remains running")
        );
    }
}
