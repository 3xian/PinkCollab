//! Owns the OMP process tree on Windows. The job's last handle closing is also a backstop for an
//! unclean Gateway exit; explicit termination still waits for the job to become empty.
use anyhow::{Context, Result, bail};
use std::{
    mem::{size_of, zeroed},
    os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle},
    process::Child,
    time::{Duration, Instant},
};
use windows_sys::Win32::Foundation::INVALID_HANDLE_VALUE;
use windows_sys::Win32::System::Diagnostics::ToolHelp::{
    CreateToolhelp32Snapshot, TH32CS_SNAPTHREAD, THREADENTRY32, Thread32First, Thread32Next,
};
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    JOBOBJECT_BASIC_ACCOUNTING_INFORMATION, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
    JobObjectBasicAccountingInformation, JobObjectExtendedLimitInformation,
    QueryInformationJobObject, SetInformationJobObject, TerminateJobObject,
};
use windows_sys::Win32::System::Threading::{OpenThread, ResumeThread, THREAD_SUSPEND_RESUME};

pub(crate) struct Job(OwnedHandle);

impl Job {
    pub(crate) fn attach_and_resume(child: &Child) -> Result<Self> {
        // SAFETY: A null name creates a private job; the returned owned handle is closed exactly
        // once by OwnedHandle. Every Win32 call below receives a valid handle and initialized data.
        let raw = unsafe { CreateJobObjectW(std::ptr::null(), std::ptr::null()) };
        if raw.is_null() {
            return Err(std::io::Error::last_os_error()).context("cannot create OMP job");
        }
        let job = Self(unsafe { OwnedHandle::from_raw_handle(raw) });
        let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = unsafe { zeroed() };
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        let set = unsafe {
            SetInformationJobObject(
                job.0.as_raw_handle(),
                JobObjectExtendedLimitInformation,
                (&raw const limits).cast(),
                size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            )
        };
        if set == 0 {
            return Err(std::io::Error::last_os_error()).context("cannot configure OMP job");
        }
        let assigned =
            unsafe { AssignProcessToJobObject(job.0.as_raw_handle(), child.as_raw_handle()) };
        if assigned == 0 {
            return Err(std::io::Error::last_os_error()).context("cannot assign OMP to job");
        }
        resume_initial_thread(child.id())?;
        Ok(job)
    }

    fn active_processes(&self) -> Result<u32> {
        let mut accounting: JOBOBJECT_BASIC_ACCOUNTING_INFORMATION = unsafe { zeroed() };
        let queried = unsafe {
            QueryInformationJobObject(
                self.0.as_raw_handle(),
                JobObjectBasicAccountingInformation,
                (&raw mut accounting).cast(),
                size_of::<JOBOBJECT_BASIC_ACCOUNTING_INFORMATION>() as u32,
                std::ptr::null_mut(),
            )
        };
        if queried == 0 {
            return Err(std::io::Error::last_os_error()).context("cannot inspect OMP job");
        }
        Ok(accounting.ActiveProcesses)
    }

    pub(crate) fn empty(&self) -> Result<bool> {
        Ok(self.active_processes()? == 0)
    }

    pub(crate) fn terminate_and_wait(&self) -> Result<()> {
        if self.empty()? {
            return Ok(());
        }
        let terminated = unsafe { TerminateJobObject(self.0.as_raw_handle(), 1) };
        if terminated == 0 && !self.empty()? {
            return Err(std::io::Error::last_os_error()).context("cannot terminate OMP job");
        }
        let deadline = Instant::now() + Duration::from_secs(3);
        loop {
            if self.empty()? {
                return Ok(());
            }
            if Instant::now() >= deadline {
                bail!("OMP job still has active processes after termination");
            }
            std::thread::sleep(Duration::from_millis(20));
        }
    }
}

/// `Command` only exposes the process handle. A suspended process has exactly its initial
/// thread, which ToolHelp can locate by owner PID before any child code is allowed to run.
fn resume_initial_thread(pid: u32) -> Result<()> {
    let raw = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0) };
    if raw == INVALID_HANDLE_VALUE {
        return Err(std::io::Error::last_os_error()).context("cannot enumerate OMP initial thread");
    }
    let snapshot = unsafe { OwnedHandle::from_raw_handle(raw) };
    let mut entry: THREADENTRY32 = unsafe { zeroed() };
    entry.dwSize = size_of::<THREADENTRY32>() as u32;
    let mut found = unsafe { Thread32First(snapshot.as_raw_handle(), &raw mut entry) } != 0;
    while found {
        if entry.th32OwnerProcessID == pid {
            let raw_thread = unsafe { OpenThread(THREAD_SUSPEND_RESUME, 0, entry.th32ThreadID) };
            if raw_thread.is_null() {
                return Err(std::io::Error::last_os_error())
                    .context("cannot open OMP initial thread");
            }
            let thread = unsafe { OwnedHandle::from_raw_handle(raw_thread) };
            if unsafe { ResumeThread(thread.as_raw_handle()) } == u32::MAX {
                return Err(std::io::Error::last_os_error())
                    .context("cannot resume OMP initial thread");
            }
            return Ok(());
        }
        found = unsafe { Thread32Next(snapshot.as_raw_handle(), &raw mut entry) } != 0;
    }
    bail!("OMP initial thread missing before job assignment")
}
