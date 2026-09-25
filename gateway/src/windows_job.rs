//! Owns the OMP process tree on Windows. The job's last handle closing is also a backstop for an
//! unclean Gateway exit; explicit termination still waits for the job to become empty.
use anyhow::{Context, Result, bail};
use std::{
    mem::{size_of, zeroed},
    os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle},
    process::Child,
    time::{Duration, Instant},
};
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    JOBOBJECT_BASIC_ACCOUNTING_INFORMATION, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
    JobObjectBasicAccountingInformation, JobObjectExtendedLimitInformation,
    QueryInformationJobObject, SetInformationJobObject, TerminateJobObject,
};

pub(crate) struct Job(OwnedHandle);

impl Job {
    pub(crate) fn attach(child: &Child) -> Result<Self> {
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
