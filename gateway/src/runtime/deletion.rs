use super::*;
use std::{fs, io::ErrorKind, path::Path};

#[derive(Debug)]
pub enum DeleteError {
    Busy,
    ExternalBusy,
    Failed,
}

impl SessionDirectory {
    pub async fn delete(&self, id: &str) -> std::result::Result<(), DeleteError> {
        let _lifecycle = self.lock_session_lifecycle(id).await;
        let _adoption = self.adoption.lock().await;
        self.delete_detached(id).await.map_err(|error| {
            eprintln!("Session deletion failed for {id}: {error}");
            if error.to_string().contains("external_session_busy") {
                DeleteError::ExternalBusy
            } else if error.to_string().contains("session_busy") {
                DeleteError::Busy
            } else {
                DeleteError::Failed
            }
        })
    }

    async fn delete_detached(&self, id: &str) -> Result<()> {
        let lookup = id.to_owned();
        ensure!(
            !self
                .store
                .run(move |s| s.session_delete_busy(&lookup))
                .await?,
            "session_busy"
        );
        let Some(view) = self.view(id).await? else {
            return Ok(());
        };
        if let Some(controller) = self.active_controller(id).await {
            ensure!(
                controller.state.lock().await.runtime.is_none(),
                "session_busy"
            );
        }
        let reference = view.session.engine_session_ref.clone();
        if let Some(reference) = &reference {
            let path = Path::new(reference);
            ensure!(
                path.is_absolute() && path.extension().is_some_and(|e| e == "jsonl"),
                "Invalid OMP session path"
            );
            let checked_path = path.to_owned();
            tokio::task::spawn_blocking(move || crate::discovery::check_writer(&checked_path))
                .await??;
            match tokio::fs::symlink_metadata(path).await {
                Ok(metadata) => {
                    ensure!(
                        !is_link(&metadata) && metadata.is_file(),
                        "Invalid OMP session file"
                    );
                    let lookup = id.to_owned();
                    if let Some(expected) =
                        self.store.run(move |s| s.adopted_omp_id(&lookup)).await?
                    {
                        crate::discovery::validate_mapping(
                            &view.session,
                            &expected,
                            self.browser.clone(),
                        )
                        .await?;
                    }
                    crate::discovery::ensure_quiet(path).await?;
                }
                Err(error) if error.kind() == ErrorKind::NotFound => {}
                Err(error) => return Err(error.into()),
            }
            // Legacy duplicate mappings must never lose another task's history.
            let lookup = id.to_owned();
            let reference = path.to_owned();
            let shared = self
                .store
                .run(move |s| s.shared_reference(&lookup, &reference))
                .await?;
            ensure!(!shared, "Shared OMP history cannot be deleted");
        }
        // Materialize a discovered record before deleting files so a partial failure
        // remains visible and retryable, including across Gateway restarts.
        if view.origin == crate::protocol::SessionOrigin::Discovered {
            let source = self
                .discovered(id)
                .await?
                .ok_or_else(|| anyhow::anyhow!("History changed"))?;
            self.store.run(move |s| s.adopt(&source)).await?;
        }
        let store = self.store.clone();
        let id_owned = id.to_owned();
        tokio::task::spawn_blocking(move || -> Result<()> {
            ensure!(
                id_owned.starts_with("sess_")
                    && id_owned[5..].bytes().all(|b| b.is_ascii_hexdigit()),
                "Invalid session ID"
            );
            let uploads = store.uploads_dir().join(&id_owned);
            // Resolve the parent before recursive deletion, and refuse links/junctions.
            if uploads.exists() {
                ensure!(
                    !is_link(&fs::symlink_metadata(store.uploads_dir())?),
                    "Invalid uploads root"
                );
                ensure!(
                    uploads.canonicalize()?.parent()
                        == Some(store.uploads_dir().canonicalize()?.as_path()),
                    "Invalid uploads directory"
                );
                remove_tree(&uploads)?;
            }
            if let Some(reference) = reference {
                let path = Path::new(&reference);
                let artifacts = path.with_extension("");
                if artifacts.exists() {
                    let parent = path
                        .parent()
                        .ok_or_else(|| anyhow::anyhow!("Missing history parent"))?
                        .canonicalize()?;
                    ensure!(
                        artifacts.canonicalize()?.parent() == Some(parent.as_path()),
                        "Invalid artifacts directory"
                    );
                    remove_tree(&artifacts)?;
                }
                crate::discovery::check_writer(path)?;
                match fs::remove_file(path) {
                    Ok(()) => {}
                    Err(error) if error.kind() == ErrorKind::NotFound => {}
                    Err(error) => return Err(error.into()),
                }
            }
            store.delete_session(&id_owned)
        })
        .await??;
        self.controllers.lock().await.remove(id);
        self.refresh_discovery().await?;
        self.bus.publish(ServerEvent::SessionRemove {
            session_id: id.into(),
        });
        Ok(())
    }
}

fn is_link(metadata: &fs::Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        metadata.file_attributes() & 0x400 != 0
    }
    #[cfg(not(windows))]
    {
        metadata.file_type().is_symlink()
    }
}

fn remove_tree(path: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(path)?;
    ensure!(
        !is_link(&metadata) && metadata.is_dir(),
        "Refusing linked session directory"
    );
    for entry in fs::read_dir(path)? {
        let path = entry?.path();
        let metadata = fs::symlink_metadata(&path)?;
        ensure!(!is_link(&metadata), "Refusing linked session artifact");
        if metadata.is_dir() {
            remove_tree(&path)?;
        } else {
            fs::remove_file(&path)?;
        }
    }
    fs::remove_dir(path)?;
    Ok(())
}
