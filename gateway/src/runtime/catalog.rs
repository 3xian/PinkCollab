//! Bounded directory selection and summary projection; never reads detail receipts.
use super::*;
use crate::protocol::SessionOrigin;

pub struct SessionCatalogPage {
    pub sessions: Vec<SessionSummary>,
    pub has_more: bool,
    pub catalog_version: String,
}

impl SessionDirectory {
    pub async fn catalog_ledger(&self) -> Result<crate::wire::Catalog> {
        let sources = self.discoveries().await?;
        self.store
            .run(move |store| store.discovered_catalog_ledger(sources))
            .await
    }

    pub async fn list_page(
        &self,
        after: Option<(&str, &str)>,
        limit: usize,
    ) -> Result<SessionCatalogPage> {
        let sources = self.discoveries().await?;
        let after = after.map(|(stamp, id)| (stamp.to_owned(), id.to_owned()));
        let cursor = after.clone();
        let stored = self
            .store
            .run(move |store| {
                store.catalog_page_records(
                    sources,
                    cursor
                        .as_ref()
                        .map(|(stamp, id)| (stamp.as_str(), id.as_str())),
                    limit,
                )
            })
            .await?;
        let discovered = stored
            .sources
            .into_iter()
            .map(|s| s.record(stored.host_id.clone()))
            .collect();
        let (records, has_more) = creation_page(
            stored.managed,
            discovered,
            after
                .as_ref()
                .map(|(stamp, id)| (stamp.as_str(), id.as_str())),
            limit,
            stored.has_more,
        );
        let sessions = self.summarize(records).await;
        let mut ledger = stored.ledger;
        // Controllers can advance after the SQLite read. Fingerprint the metadata
        // actually served, so a page never claims provenance for different rows.
        for summary in &sessions {
            ledger.insert(&serde_json::to_value(&summary.session)?);
        }
        Ok(SessionCatalogPage {
            sessions,
            has_more,
            catalog_version: ledger.version(),
        })
    }

    pub async fn bootstrap(
        &self,
        focus: Option<&str>,
    ) -> Result<(Vec<SessionSummary>, Option<String>, crate::wire::Catalog)> {
        let sources = self.discoveries().await?;
        let focus = focus.map(str::to_owned);
        let read_focus = focus.clone();
        let stored = self
            .store
            .run(move |store| store.bootstrap_records(sources, read_focus.as_deref()))
            .await?;
        let page = stored.page;
        let discovered: Vec<_> = page
            .sources
            .into_iter()
            .map(|s| s.record(page.host_id.clone()))
            .collect();
        let focused = stored
            .focus
            .map(|s| (s, SessionOrigin::Managed))
            .or_else(|| {
                discovered
                    .iter()
                    .find(|s| Some(s.id.as_str()) == focus.as_deref())
                    .cloned()
                    .map(|s| (s, SessionOrigin::Discovered))
            });
        let (mut records, more) =
            creation_page(page.managed, discovered.clone(), None, 50, page.has_more);
        // Capture the base creation boundary before adding recent, attached or focused rows.
        let cursor = more
            .then(|| {
                records.last().map(|(s, _)| {
                    hex::encode(
                        serde_json::to_vec(
                            &json!({"created_at": s.created_at.to_rfc3339(), "id": s.id}),
                        )
                        .unwrap(),
                    )
                })
            })
            .flatten();
        let mut recent: Vec<_> = stored
            .recent
            .into_iter()
            .map(|s| (s, SessionOrigin::Managed))
            .chain(
                discovered
                    .into_iter()
                    .map(|s| (s, SessionOrigin::Discovered)),
            )
            .collect();
        recent.sort_by(|(a, _), (b, _)| {
            b.last_activity_at
                .cmp(&a.last_activity_at)
                .then_with(|| b.id.cmp(&a.id))
        });
        let mut ids: HashSet<_> = records.iter().map(|(s, _)| s.id.clone()).collect();
        records.extend(
            recent
                .into_iter()
                .take(20)
                .chain(focused)
                .filter(|(s, _)| ids.insert(s.id.clone())),
        );
        let mut summaries = self.summarize(records).await;
        let controllers: Vec<_> = self
            .controllers
            .lock()
            .await
            .values()
            .filter_map(std::sync::Weak::upgrade)
            .collect();
        for controller in controllers {
            let mut state = controller.state.lock().await;
            if ids.contains(&state.session.id) {
                continue;
            }
            let runtime = state.capture_runtime().as_ref().map(RuntimeDto::from);
            if runtime.is_some() {
                ids.insert(state.session.id.clone());
                summaries.push(SessionSummary {
                    session: SessionDto::from(&state.session),
                    runtime,
                });
            }
        }
        Ok((summaries, cursor, page.ledger))
    }

    pub(super) async fn summarize(
        &self,
        records: Vec<(SessionRecord, SessionOrigin)>,
    ) -> Vec<SessionSummary> {
        let mut result = Vec::with_capacity(records.len());
        for (session, origin) in records {
            if let Some(controller) = self.active_controller(&session.id).await {
                let mut state = controller.state.lock().await;
                result.push(SessionSummary {
                    session: SessionDto::from(&state.session),
                    runtime: state.capture_runtime().as_ref().map(RuntimeDto::from),
                });
            } else {
                result.push(SessionSummary {
                    session: SessionDto {
                        origin,
                        ..SessionDto::from(&session)
                    },
                    runtime: None,
                });
            }
        }
        result
    }
}

pub(super) fn creation_page(
    managed: Vec<SessionRecord>,
    discovered: Vec<SessionRecord>,
    after: Option<(&str, &str)>,
    limit: usize,
    managed_more: bool,
) -> (Vec<(SessionRecord, SessionOrigin)>, bool) {
    let mut records: Vec<_> = managed
        .into_iter()
        .map(|s| (s, SessionOrigin::Managed))
        .chain(
            discovered
                .into_iter()
                .filter(|s| {
                    after.is_none_or(|(stamp, id)| {
                        let created = s.created_at.to_rfc3339();
                        created.as_str() < stamp || (created == stamp && s.id.as_str() < id)
                    })
                })
                .map(|s| (s, SessionOrigin::Discovered)),
        )
        .collect();
    records.sort_by(|(a, _), (b, _)| {
        b.created_at
            .cmp(&a.created_at)
            .then_with(|| b.id.cmp(&a.id))
    });
    let more = managed_more || records.len() > limit;
    records.truncate(limit);
    (records, more)
}
