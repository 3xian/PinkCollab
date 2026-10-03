//! A detached desktop process with an instance lock and graceful same-user shutdown.
use anyhow::{Context, Result, ensure};
use pinkcollab_gateway::{
    config::Config,
    storage::{self, Store},
};
use serde::{Deserialize, Serialize};
use std::{
    os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle},
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, Instant},
};
use windows_sys::Win32::{
    Foundation::{
        ERROR_ALREADY_EXISTS, ERROR_INVALID_PARAMETER, FILETIME, GetLastError, WAIT_OBJECT_0,
    },
    System::Threading::{
        CREATE_NO_WINDOW, CreateEventW, CreateProcessW, EVENT_MODIFY_STATE, GetCurrentProcess,
        GetExitCodeProcess, GetProcessTimes, OpenEventW, OpenProcess, PROCESS_INFORMATION,
        PROCESS_QUERY_LIMITED_INFORMATION, STARTUPINFOW, SYNCHRONIZATION_SYNCHRONIZE, SetEvent,
        WaitForSingleObject,
    },
};

/// Do not inherit the CLI's pipe handles: otherwise `pinkcollab service start` invoked
/// by another program cannot finish reading its output until the Gateway exits.
pub fn spawn(binary: &Path, dir: &Path) -> Result<OwnedHandle> {
    use std::os::windows::ffi::OsStrExt;
    let mut command: Vec<u16> = crate::service::windows_arguments(binary, dir, "background-run")
        .encode_utf16()
        .chain(Some(0))
        .collect();
    let binary: Vec<u16> = binary.as_os_str().encode_wide().chain(Some(0)).collect();
    let mut startup: STARTUPINFOW = unsafe { std::mem::zeroed() };
    startup.cb = std::mem::size_of::<STARTUPINFOW>() as u32;
    let mut process: PROCESS_INFORMATION = unsafe { std::mem::zeroed() };
    ensure!(
        unsafe {
            CreateProcessW(
                binary.as_ptr(),
                command.as_mut_ptr(),
                std::ptr::null(),
                std::ptr::null(),
                0,
                CREATE_NO_WINDOW,
                std::ptr::null(),
                std::ptr::null(),
                &startup,
                &mut process,
            )
        } != 0,
        "cannot start background Gateway: {}",
        std::io::Error::last_os_error()
    );
    let handle = unsafe { OwnedHandle::from_raw_handle(process.hProcess) };
    drop(unsafe { OwnedHandle::from_raw_handle(process.hThread) });
    Ok(handle)
}
pub fn exit_code(process: &OwnedHandle) -> Result<Option<u32>> {
    if unsafe { WaitForSingleObject(process.as_raw_handle(), 0) } != WAIT_OBJECT_0 {
        return Ok(None);
    }
    let mut code = 0;
    ensure!(
        unsafe { GetExitCodeProcess(process.as_raw_handle(), &mut code) } != 0,
        "cannot read Gateway exit code: {}",
        std::io::Error::last_os_error()
    );
    Ok(Some(code))
}

#[derive(Serialize, Deserialize)]
struct Process {
    pid: u32,
    created: u64,
    #[serde(default)]
    ready: bool,
}
struct RunningProcess {
    record: Process,
    handle: OwnedHandle,
}
fn marker(dir: &Path) -> PathBuf {
    dir.join("gateway-process.json")
}
fn event_name(dir: &Path) -> Result<Vec<u16>> {
    use sha2::{Digest, Sha256};
    let hash = hex::encode(Sha256::digest(
        dir.canonicalize()?
            .to_string_lossy()
            .to_lowercase()
            .as_bytes(),
    ));
    Ok(format!("Global\\PinkCollab-{hash}-stop\0")
        .encode_utf16()
        .collect())
}
fn created(handle: windows_sys::Win32::Foundation::HANDLE) -> Result<u64> {
    let mut creation: FILETIME = unsafe { std::mem::zeroed() };
    let mut exit = creation;
    let mut kernel = creation;
    let mut user = creation;
    ensure!(
        unsafe { GetProcessTimes(handle, &mut creation, &mut exit, &mut kernel, &mut user) } != 0,
        "cannot read Gateway process identity: {}",
        std::io::Error::last_os_error()
    );
    Ok((u64::from(creation.dwHighDateTime) << 32) | u64::from(creation.dwLowDateTime))
}
fn running_process(dir: &Path) -> Result<Option<RunningProcess>> {
    let bytes = match std::fs::read(marker(dir)) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let process: Process =
        serde_json::from_slice(&bytes).context("cannot read background Gateway process record")?;
    let handle = unsafe {
        OpenProcess(
            PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZATION_SYNCHRONIZE,
            0,
            process.pid,
        )
    };
    if handle.is_null() {
        let error = std::io::Error::last_os_error();
        if error.raw_os_error() == Some(ERROR_INVALID_PARAMETER as i32) {
            return Ok(None);
        }
        return Err(error).context("cannot inspect background Gateway process");
    }
    let handle = unsafe { OwnedHandle::from_raw_handle(handle) };
    if unsafe { WaitForSingleObject(handle.as_raw_handle(), 0) } == WAIT_OBJECT_0
        || created(handle.as_raw_handle())? != process.created
    {
        return Ok(None);
    }
    Ok(Some(RunningProcess {
        record: process,
        handle,
    }))
}
pub fn is_running(dir: &Path) -> Result<bool> {
    Ok(running_process(dir)?.is_some())
}
pub fn is_ready(dir: &Path) -> Result<bool> {
    Ok(running_process(dir)?.is_some_and(|process| process.record.ready))
}
pub fn stop(dir: &Path) -> Result<()> {
    let Some(process) = running_process(dir)? else {
        return Ok(());
    };
    let name = event_name(dir)?;
    let event = unsafe { OpenEventW(EVENT_MODIFY_STATE, 0, name.as_ptr()) };
    ensure!(
        !event.is_null(),
        "cannot open Gateway shutdown signal: {}",
        std::io::Error::last_os_error()
    );
    let event = unsafe { OwnedHandle::from_raw_handle(event) };
    ensure!(
        unsafe { SetEvent(event.as_raw_handle()) } != 0,
        "cannot signal Gateway shutdown: {}",
        std::io::Error::last_os_error()
    );
    let deadline = Instant::now() + Duration::from_secs(45);
    while unsafe { WaitForSingleObject(process.handle.as_raw_handle(), 100) } != WAIT_OBJECT_0 {
        ensure!(
            Instant::now() < deadline,
            "Gateway has not stopped; retry after its active work exits"
        );
    }
    Ok(())
}
struct Registration {
    dir: PathBuf,
    event: OwnedHandle,
}
impl Drop for Registration {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(marker(&self.dir));
    }
}
pub async fn run(dir: PathBuf) -> Result<()> {
    use windows_sys::Win32::System::Console::{STD_ERROR_HANDLE, STD_OUTPUT_HANDLE, SetStdHandle};
    let log = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(dir.join("gateway.log"))?;
    ensure!(
        unsafe { SetStdHandle(STD_OUTPUT_HANDLE, log.as_raw_handle()) } != 0
            && unsafe { SetStdHandle(STD_ERROR_HANDLE, log.as_raw_handle()) } != 0,
        "cannot redirect Gateway logs: {}",
        std::io::Error::last_os_error()
    );
    let result = run_inner(dir).await;
    if let Err(error) = &result {
        eprintln!("Background Gateway failed: {error:#}");
    }
    result
}
async fn run_inner(dir: PathBuf) -> Result<()> {
    let name = event_name(&dir)?;
    let event = unsafe { CreateEventW(std::ptr::null(), 1, 0, name.as_ptr()) };
    let error = unsafe { GetLastError() };
    ensure!(
        !event.is_null(),
        "cannot create Gateway shutdown signal: {}",
        std::io::Error::from_raw_os_error(error as i32)
    );
    let event = unsafe { OwnedHandle::from_raw_handle(event) };
    // A second login session or simultaneous start must not replace the active process record.
    if error == ERROR_ALREADY_EXISTS {
        return Ok(());
    }
    restore_environment(&dir)?;
    let config = Config::load(&dir)?;
    let store = Arc::new(Store::open(&dir)?);
    let registration = Registration {
        dir: dir.clone(),
        event,
    };
    let mut process = Process {
        pid: std::process::id(),
        created: created(unsafe { GetCurrentProcess() })?,
        ready: false,
    };
    storage::replace_private_file(&marker(&dir), &serde_json::to_vec(&process)?)?;
    let handle = axum_server::Handle::new();
    let serving = super::serve_until(
        config,
        store,
        async {
            loop {
                if unsafe { WaitForSingleObject(registration.event.as_raw_handle(), 0) }
                    == WAIT_OBJECT_0
                {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        },
        handle.clone(),
    );
    tokio::pin!(serving);
    tokio::select! {
        result = &mut serving => result,
        address = handle.listening() => {
            if address.is_some() {
                process.ready = true;
                storage::replace_private_file(&marker(&dir), &serde_json::to_vec(&process)?)?;
            }
            serving.await
        }
    }
}
fn restore_environment(dir: &Path) -> Result<()> {
    let values: std::collections::BTreeMap<String, String> =
        serde_json::from_slice(&std::fs::read(dir.join("service-environment.json"))?)
            .context("cannot read installed Gateway environment")?;
    for key in ["PATH", "USERPROFILE", "HOME", "APPDATA", "LOCALAPPDATA"] {
        if let Some(value) = values.get(key) {
            unsafe {
                std::env::set_var(key, value);
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stale_pid_does_not_identify_or_stop_an_unrelated_process() {
        let root = tempfile::tempdir().unwrap();
        let process = Process {
            pid: std::process::id(),
            created: 0,
            ready: true,
        };
        std::fs::write(marker(root.path()), serde_json::to_vec(&process).unwrap()).unwrap();
        assert!(!is_running(root.path()).unwrap());
        stop(root.path()).unwrap();
    }
}
