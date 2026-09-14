use anyhow::{Context, Result};
use pinkcollab_gateway::{config::Config, storage::Store};
use std::{
    ffi::OsString,
    path::PathBuf,
    sync::{Arc, OnceLock},
    time::Duration,
};
use windows_service::{
    define_windows_service,
    service::{
        ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceState, ServiceStatus,
        ServiceType,
    },
    service_control_handler::{self, ServiceControlHandlerResult},
    service_dispatcher,
};

const NAME: &str = "PinkCollab";
static DATA_DIR: OnceLock<PathBuf> = OnceLock::new();
define_windows_service!(ffi_service_main, service_main);
pub fn dispatch(dir: PathBuf) -> Result<()> {
    DATA_DIR
        .set(dir)
        .map_err(|_| anyhow::anyhow!("service already initialized"))?;
    service_dispatcher::start(NAME, ffi_service_main)
        .context("service must be started by the Windows Service Control Manager")
}
fn service_main(_arguments: Vec<OsString>) {
    if let Err(error) = run() {
        eprintln!("PinkCollab service failed: {error:#}");
    }
}
fn status(state: ServiceState, code: u32) -> ServiceStatus {
    ServiceStatus {
        service_type: ServiceType::OWN_PROCESS,
        current_state: state,
        controls_accepted: if state == ServiceState::Running {
            ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN
        } else {
            ServiceControlAccept::empty()
        },
        exit_code: ServiceExitCode::Win32(code),
        checkpoint: 0,
        wait_hint: Duration::from_secs(10),
        process_id: None,
    }
}
fn run() -> Result<()> {
    let (stop, mut stopped) = tokio::sync::watch::channel(false);
    let handler = service_control_handler::register(NAME, move |control| match control {
        ServiceControl::Stop | ServiceControl::Shutdown => {
            let _ = stop.send(true);
            ServiceControlHandlerResult::NoError
        }
        ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
        _ => ServiceControlHandlerResult::NotImplemented,
    })?;
    handler.set_service_status(status(ServiceState::StartPending, 0))?;
    let result = (|| -> Result<()> {
        let dir = DATA_DIR
            .get()
            .context("service data directory unavailable")?;
        let config = Config::load(dir)?;
        let store = Arc::new(Store::open(dir)?);
        let runtime = tokio::runtime::Runtime::new()?;
        handler.set_service_status(status(ServiceState::Running, 0))?;
        runtime.block_on(super::serve_until(config, store, async move {
            while !*stopped.borrow() {
                if stopped.changed().await.is_err() {
                    break;
                }
            }
        }))
    })();
    handler.set_service_status(status(ServiceState::Stopped, u32::from(result.is_err())))?;
    result
}
