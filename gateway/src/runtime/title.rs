use super::SessionController;
use crate::domain::SessionRecord;
use anyhow::Result;

impl SessionController {
    pub(super) async fn update_prompt_title(&self, generation: &str, message: &str) -> Result<()> {
        let title = prompt_prefix(message);
        let id = {
            let state = self.state.lock().await;
            if title.is_empty()
                || title == state.session.title
                || state
                    .runtime
                    .as_ref()
                    .is_none_or(|r| r.generation != generation)
            {
                return Ok(());
            }
            state.session.id.clone()
        };
        let owned_generation = generation.to_owned();
        let title = title.to_owned();
        let saved = self
            .store
            .run(move |store| store.set_session_title(&id, &owned_generation, &title))
            .await?;
        if let Some(saved) = saved {
            self.apply_prompt_title(saved).await;
        }
        Ok(())
    }

    async fn apply_prompt_title(&self, saved: SessionRecord) {
        let mut state = self.state.lock().await;
        // Persistence validates the generation; a committed title outlives that runtime.
        if saved.metadata_revision > state.session.metadata_revision {
            state.session = saved;
            self.publish_state(&mut state);
        }
    }
}

fn prompt_prefix(message: &str) -> String {
    message
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(80)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{events::Bus, runtime::ControllerState, storage::Store, workspace::Browser};
    use std::{
        collections::{HashMap, HashSet},
        sync::Arc,
    };
    use tokio::sync::{Mutex, Semaphore};

    #[test]
    fn title_is_a_single_line_unicode_prefix() {
        assert_eq!(
            prompt_prefix("  最新消息\n  第二行\t内容  "),
            "最新消息 第二行 内容"
        );
        assert_eq!(prompt_prefix(&"修复🙂".repeat(40)).chars().count(), 80);
        assert_eq!(prompt_prefix(" \n\t"), "");
    }

    #[tokio::test]
    async fn committed_title_applies_after_exit_and_rejects_stale_revisions() {
        let dir = tempfile::tempdir().unwrap();
        let store = Arc::new(Store::open(&dir.path().join("data")).unwrap());
        let bus = Arc::new(Bus::default());
        let mut events = bus.subscribe();
        let now = chrono::Utc::now();
        let session = SessionRecord {
            id: "session".into(),
            host_id: store.host_id().unwrap(),
            cwd: crate::workspace::display(dir.path()),
            title: "Original".into(),
            metadata_revision: 1,
            created_at: now,
            updated_at: now,
            archived_at: None,
            engine_session_ref: None,
        };
        store
            .create_v2("client", "create", "create", session.clone())
            .unwrap();
        let controller = SessionController {
            state: Mutex::new(ControllerState {
                session,
                runtime: None,
                projection: None,
                work_clock: None,
                messages: Vec::new(),
                finalized_messages: HashSet::new(),
                pending_prompt_results: HashMap::new(),
                settled_revision: 0,
                dirty_messages: HashSet::new(),
                published_messages: HashMap::new(),
                removed_messages: Vec::new(),
                display_flush_scheduled: false,
            }),
            ordinary_dispatch: Mutex::new(()),
            prompt_interrupt_admission: Arc::new(Mutex::new(())),
            feedback_order: Mutex::new(()),
            store: store.clone(),
            browser: Arc::new(Browser::new(&[dir.path().to_path_buf()]).unwrap()),
            bus,
            quota: Arc::new(Semaphore::new(1)),
            executable: String::new(),
            args: Vec::new(),
        };
        assert!(store.reserve_runtime("session", "generation").unwrap());
        let older = store
            .set_session_title("session", "generation", "Earlier prompt")
            .unwrap()
            .unwrap();
        let latest = store
            .set_session_title("session", "generation", "Latest prompt")
            .unwrap()
            .unwrap();
        // Model the post-commit handoff after exit without scheduler timing or a process.
        assert!(store.release_runtime("session", "generation").unwrap());
        controller.apply_prompt_title(latest.clone()).await;
        let view = controller.view().await.unwrap();
        assert!(view.runtime.is_none());
        assert_eq!(view.session.title, "Latest prompt");
        assert_eq!(view.session.metadata_revision, latest.metadata_revision);
        let detail = events.try_recv().unwrap();
        let crate::events::Event::Update { event, .. } = detail else {
            panic!("expected update")
        };
        let crate::protocol::ServerEvent::SessionState { summary, .. } = event.as_ref() else {
            panic!("expected session state")
        };
        assert_eq!(summary.session.title, "Latest prompt");
        assert!(summary.runtime.is_none());
        assert!(
            serde_json::to_value(&summary.session)
                .unwrap()
                .get("metadataRevision")
                .is_none()
        );
        controller.apply_prompt_title(older).await;
        controller.apply_prompt_title(latest.clone()).await;
        let view = controller.view().await.unwrap();
        assert_eq!(view.session.title, "Latest prompt");
        assert_eq!(view.session.metadata_revision, latest.metadata_revision);
        assert!(matches!(
            events.try_recv(),
            Err(tokio::sync::broadcast::error::TryRecvError::Empty)
        ));
    }
}
