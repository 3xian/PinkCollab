use crate::storage::Store;
use anyhow::Result;

/// Only OS evidence of complete containment exit can release an orphaned lease.
/// Legacy leases without that evidence require explicit administrative recovery.
pub(super) fn recover(store: &Store, session_id: &str) -> Result<()> {
    #[cfg(windows)]
    if let Some((generation, name)) = store.runtime_job(session_id)?
        && crate::windows_job::Job::absent(&name)?
    {
        store.finish_runtime(session_id, &generation)?;
    }
    #[cfg(target_os = "linux")]
    if let Some((generation, boot_id, group_id)) = store.runtime_process_group(session_id)?
        && crate::omp::linux_runtime_exited(&boot_id, group_id)?
    {
        store.finish_runtime(session_id, &generation)?;
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    let _ = (store, session_id);
    Ok(())
}
