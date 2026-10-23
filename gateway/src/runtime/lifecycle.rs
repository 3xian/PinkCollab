//! Session mutation boundaries shared by HTTP and runtime admission.
use super::*;

#[derive(Debug)]
pub enum CreateError {
    Deleted,
    Conflict,
    Persistence,
}

#[derive(Debug)]
pub enum UploadError {
    NotFound,
    Persistence,
    Invalid,
    Failed,
}

impl SessionDirectory {
    pub(super) async fn lock_session_lifecycle(&self, id: &str) -> OwnedMutexGuard<()> {
        let gate = {
            let mut gates = self.lifecycle.lock().await;
            gates.retain(|_, gate| gate.strong_count() > 0);
            if let Some(gate) = gates.get(id).and_then(std::sync::Weak::upgrade) {
                gate
            } else {
                let gate = Arc::new(Mutex::new(()));
                gates.insert(id.into(), Arc::downgrade(&gate));
                gate
            }
        };
        gate.lock_owned().await
    }

    pub async fn create(
        &self,
        client_id: String,
        command_id: String,
        fingerprint: String,
        record: SessionRecord,
    ) -> std::result::Result<(SessionRecord, bool), CreateError> {
        let (record, replayed) = self
            .store
            .run(move |store| store.create_v2(&client_id, &command_id, &fingerprint, record))
            .await
            .map_err(|error| match error.to_string().as_str() {
                "session_deleted" => CreateError::Deleted,
                "idempotency_conflict" => CreateError::Conflict,
                _ => CreateError::Persistence,
            })?;
        // Snapshots can expose the row before create returns. A concurrent deletion
        // must not be followed by a delayed creation event that resurrects the task.
        let _lifecycle = self.lock_session_lifecycle(&record.id).await;
        let lookup = record.id.clone();
        if self
            .store
            .run(move |store| store.v2_session(&lookup))
            .await
            .map_err(|_| CreateError::Persistence)?
            .is_none()
        {
            return Err(CreateError::Deleted);
        }
        if !replayed {
            self.publish_created(&record).await;
        }
        Ok((record, replayed))
    }

    pub async fn upload(
        &self,
        id: String,
        file_id: String,
        name: String,
        bytes: impl AsRef<[u8]> + Send + 'static,
    ) -> std::result::Result<std::path::PathBuf, UploadError> {
        let lifecycle = self.lock_session_lifecycle(&id).await;
        if self
            .view(&id)
            .await
            .map_err(|_| UploadError::Persistence)?
            .is_none()
        {
            return Err(UploadError::NotFound);
        }
        let store = self.store.clone();
        tokio::task::spawn_blocking(move || {
            // Keep deletion fenced even if the requesting client disconnects.
            let _lifecycle = lifecycle;
            uploads::save(&store, &id, &file_id, &name, bytes.as_ref())
        })
        .await
        .map_err(|_| UploadError::Failed)?
        .map_err(|error| {
            if error.downcast_ref::<std::io::Error>().is_some() {
                UploadError::Failed
            } else {
                UploadError::Invalid
            }
        })
    }
}
