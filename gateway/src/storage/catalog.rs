//! Public-metadata invalidation is transactional, including writes outside a controller.
use super::*;

pub(super) fn migrate(db: &Connection) -> Result<()> {
    let tx = rusqlite::Transaction::new_unchecked(db, rusqlite::TransactionBehavior::Immediate)?;
    if tx.query_row(
        "SELECT COUNT(*) FROM pragma_table_info('session_records') WHERE name='last_activity_at'",
        [],
        |r| r.get::<_, i64>(0),
    )? == 0
    {
        tx.execute_batch(
            "ALTER TABLE session_records ADD COLUMN last_activity_at TEXT;
            UPDATE session_records SET last_activity_at=updated_at;",
        )?;
    }
    tx.execute_batch("CREATE INDEX IF NOT EXISTS sessions_activity_order ON session_records(last_activity_at DESC,id DESC);
        CREATE TABLE IF NOT EXISTS catalog_clock (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL);
        INSERT OR IGNORE INTO catalog_clock VALUES (1,0);
        CREATE TRIGGER IF NOT EXISTS catalog_insert AFTER INSERT ON session_records BEGIN
            UPDATE catalog_clock SET revision=revision+1 WHERE id=1;
        END;
        CREATE TRIGGER IF NOT EXISTS catalog_delete AFTER DELETE ON session_records BEGIN
            UPDATE catalog_clock SET revision=revision+1 WHERE id=1;
        END;
        CREATE TRIGGER IF NOT EXISTS catalog_update AFTER UPDATE OF host_id,cwd,title,created_at,updated_at,last_activity_at ON session_records
        WHEN OLD.host_id IS NOT NEW.host_id OR OLD.cwd IS NOT NEW.cwd OR OLD.title IS NOT NEW.title
            OR OLD.created_at IS NOT NEW.created_at OR OLD.updated_at IS NOT NEW.updated_at
            OR OLD.last_activity_at IS NOT NEW.last_activity_at BEGIN
            UPDATE catalog_clock SET revision=revision+1 WHERE id=1;
        END;
        PRAGMA user_version=8;")?;
    tx.commit()?;
    Ok(())
}

pub(crate) struct CatalogRecords {
    pub managed: Vec<SessionRecord>,
    pub has_more: bool,
    pub sources: Vec<crate::discovery::OmpDiscoveredSession>,
    pub host_id: String,
    pub ledger: crate::wire::Catalog,
}

pub(crate) struct BootstrapRecords {
    pub page: CatalogRecords,
    pub recent: Vec<SessionRecord>,
    pub focus: Option<SessionRecord>,
}

impl Store {
    /// Cache only public metadata fingerprints, never runtime state or history. The
    /// revision and selected rows are read under the same SQLite transaction.
    pub fn catalog_ledger(&self) -> Result<crate::wire::Catalog> {
        let db = self.db.lock();
        let tx = db.unchecked_transaction()?;
        self.catalog_ledger_at(&tx)
    }

    fn catalog_ledger_at(&self, db: &Connection) -> Result<crate::wire::Catalog> {
        let revision = db.query_row("SELECT revision FROM catalog_clock WHERE id=1", [], |r| {
            r.get::<_, i64>(0)
        })?;
        let mut cache = self.catalog_cache.lock();
        if let Some((cached_revision, catalog)) = cache.as_ref()
            && *cached_revision == revision
        {
            return Ok(catalog.clone());
        }
        let mut catalog = crate::wire::Catalog::default();
        let mut query = db.prepare("SELECT id,host_id,cwd,title,metadata_revision,created_at,updated_at,archived_at,engine_session_ref,last_activity_at FROM session_records")?;
        for row in query.query_map([], session_columns)? {
            let record = parse_session(row?)?;
            catalog.insert(&serde_json::to_value(crate::protocol::SessionDto::from(
                &record,
            ))?);
        }
        *cache = Some((revision, catalog.clone()));
        Ok(catalog)
    }

    pub(crate) fn discovered_catalog_ledger(
        &self,
        sources: Vec<crate::discovery::OmpDiscoveredSession>,
    ) -> Result<crate::wire::Catalog> {
        let db = self.db.lock();
        let tx = db.unchecked_transaction()?;
        let sources = references::unmanaged_sources(&tx, sources)?;
        let host_id = tx.query_row("SELECT host_id FROM identity WHERE id=1", [], |r| {
            r.get::<_, String>(0)
        })?;
        let mut ledger = self.catalog_ledger_at(&tx)?;
        add_discovered(&mut ledger, &sources, &host_id)?;
        Ok(ledger)
    }

    /// One discovery generation and one SQLite read transaction define selection,
    /// ownership, total count and metadata version, even for writes without events.
    pub(crate) fn bootstrap_records(
        &self,
        sources: Vec<crate::discovery::OmpDiscoveredSession>,
        focus: Option<&str>,
    ) -> Result<BootstrapRecords> {
        let db = self.db.lock();
        let tx = db.unchecked_transaction()?;
        Ok(BootstrapRecords {
            page: self.catalog_records_at(&tx, sources, None, 50)?,
            recent: recent_sessions(&tx, 20)?,
            focus: focus
                .map(|id| read_v2_session(&tx, id))
                .transpose()?
                .flatten(),
        })
    }

    pub(crate) fn catalog_page_records(
        &self,
        sources: Vec<crate::discovery::OmpDiscoveredSession>,
        after: Option<(&str, &str)>,
        limit: usize,
    ) -> Result<CatalogRecords> {
        let db = self.db.lock();
        let tx = db.unchecked_transaction()?;
        self.catalog_records_at(&tx, sources, after, limit)
    }

    fn catalog_records_at(
        &self,
        db: &Connection,
        sources: Vec<crate::discovery::OmpDiscoveredSession>,
        after: Option<(&str, &str)>,
        limit: usize,
    ) -> Result<CatalogRecords> {
        let sources = references::unmanaged_sources(db, sources)?;
        let host_id = db.query_row("SELECT host_id FROM identity WHERE id=1", [], |r| {
            r.get::<_, String>(0)
        })?;
        let mut ledger = self.catalog_ledger_at(db)?;
        add_discovered(&mut ledger, &sources, &host_id)?;
        let (managed, has_more) = read_sessions_page(db, after, limit)?;
        Ok(CatalogRecords {
            managed,
            has_more,
            sources,
            host_id,
            ledger,
        })
    }

    pub fn touch_session(&self, id: &str, generation: &str) -> Result<Option<SessionRecord>> {
        let db = self.db.lock();
        let changed = db.execute("UPDATE session_records SET last_activity_at=?3
            WHERE id=?1 AND EXISTS(SELECT 1 FROM runtime_leases WHERE session_id=?1 AND generation=?2)
            AND (last_activity_at IS NULL OR last_activity_at < ?3)", params![id, generation, Utc::now().to_rfc3339()])?;
        if changed == 0 {
            return Ok(None);
        }
        read_v2_session(&db, id)
    }
}

fn add_discovered(
    ledger: &mut crate::wire::Catalog,
    sources: &[crate::discovery::OmpDiscoveredSession],
    host_id: &str,
) -> Result<()> {
    for source in sources {
        let mut dto = crate::protocol::SessionDto::from(&source.record(host_id.to_owned()));
        dto.origin = crate::protocol::SessionOrigin::Discovered;
        ledger.insert(&serde_json::to_value(dto)?);
    }
    Ok(())
}

fn recent_sessions(db: &Connection, limit: usize) -> Result<Vec<SessionRecord>> {
    let mut query = db.prepare("SELECT id,host_id,cwd,title,metadata_revision,created_at,updated_at,archived_at,engine_session_ref,last_activity_at FROM session_records ORDER BY last_activity_at DESC,id DESC LIMIT ?1")?;
    query
        .query_map([limit as i64], session_columns)?
        .map(|r| parse_session(r?))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn record(store: &Store) -> SessionRecord {
        let created: DateTime<Utc> = "2025-01-01T00:00:00Z".parse().unwrap();
        SessionRecord {
            id: "s".into(),
            host_id: store.host_id().unwrap(),
            cwd: "/work".into(),
            title: "task".into(),
            metadata_revision: 1,
            created_at: created,
            updated_at: created,
            last_activity_at: created,
            archived_at: None,
            engine_session_ref: None,
        }
    }
    #[test]
    fn activity_survives_restart_does_not_change_metadata_and_invalidates_ledger() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let original = record(&store);
        store.create_v2("c", "new", "fp", original.clone()).unwrap();
        let initial = store.catalog_ledger().unwrap().version();
        assert!(store.touch_session("s", "stale").unwrap().is_none());
        assert_eq!(store.catalog_ledger().unwrap().version(), initial);
        store.reserve_runtime("s", "g").unwrap();
        let touched = store.touch_session("s", "g").unwrap().unwrap();
        assert!(touched.last_activity_at > original.last_activity_at);
        assert_eq!(touched.updated_at, original.updated_at);
        assert_eq!(touched.metadata_revision, original.metadata_revision);
        assert_ne!(store.catalog_ledger().unwrap().version(), initial);
        drop(store);
        let store = Store::open(dir.path()).unwrap();
        let restored = store.v2_session("s").unwrap().unwrap();
        assert_eq!(restored.last_activity_at, touched.last_activity_at);
        let before_read = store.catalog_ledger().unwrap().version();
        store.bootstrap_records(Vec::new(), None).unwrap();
        store.v2_sessions_page(None, 50).unwrap();
        assert_eq!(store.catalog_ledger().unwrap().version(), before_read);
    }
    #[test]
    fn v7_upgrade_backfills_activity_and_detects_out_of_band_metadata() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        store.create_v2("c", "new", "fp", record(&store)).unwrap();
        drop(store);
        let db = Connection::open(dir.path().join("pinkcollab.db")).unwrap();
        db.execute_batch(
            "DROP TRIGGER catalog_update; DROP INDEX sessions_activity_order;
            ALTER TABLE session_records DROP COLUMN last_activity_at; PRAGMA user_version=7;",
        )
        .unwrap();
        drop(db);
        let store = Store::open(dir.path()).unwrap();
        let session = store.v2_session("s").unwrap().unwrap();
        assert_eq!(session.last_activity_at, session.updated_at);
        let version = store.catalog_ledger().unwrap().version();
        let db = Connection::open(dir.path().join("pinkcollab.db")).unwrap();
        db.execute(
            "UPDATE session_records SET title='external metadata' WHERE id='s'",
            [],
        )
        .unwrap();
        assert_ne!(store.catalog_ledger().unwrap().version(), version);
        assert_eq!(
            db.query_row("PRAGMA user_version", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            10
        );
    }
}
