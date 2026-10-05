//! Indexed ownership of canonical engine references. Filesystem resolution happens
//! before acquiring the Store lock; transactions only compare persisted keys.
use super::*;

pub(crate) fn reference_key(path: &Path) -> String {
    let path = path
        .canonicalize()
        .or_else(|_| {
            let parent = path
                .parent()
                .context("missing reference parent")?
                .canonicalize()?;
            Ok::<_, anyhow::Error>(
                parent.join(path.file_name().context("missing reference filename")?),
            )
        })
        .unwrap_or_else(|_| path.to_owned());
    canonical_key(&path)
}

pub(crate) fn canonical_key(path: &Path) -> String {
    let key = crate::workspace::display(path);
    #[cfg(windows)]
    {
        key.replace('/', "\\").to_lowercase()
    }
    #[cfg(not(windows))]
    {
        key
    }
}

pub(super) fn claim_reference(db: &Connection, session_id: &str, key: &str) -> Result<()> {
    db.execute(
        "INSERT INTO engine_references(reference_key,session_id) VALUES (?1,?2)
         ON CONFLICT(reference_key) DO NOTHING",
        params![key, session_id],
    )?;
    let owner: String = db.query_row(
        "SELECT session_id FROM engine_references WHERE reference_key=?1",
        [key],
        |r| r.get(0),
    )?;
    ensure!(
        owner == session_id,
        "OMP history is already managed; refresh sessions"
    );
    Ok(())
}

pub(super) fn migrate_references(db: &Connection) -> Result<()> {
    // Legacy duplicate mappings keep their records. One deterministic owner reserves
    // each key, preventing new duplicates without discarding existing conversation IDs.
    let existing = {
        let mut query = db.prepare("SELECT id,engine_session_ref FROM session_records WHERE engine_session_ref IS NOT NULL ORDER BY id")?;
        query
            .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?
            .collect::<rusqlite::Result<Vec<_>>>()?
    };
    let keys: Vec<_> = existing
        .into_iter()
        .map(|(id, path)| (id, reference_key(Path::new(&path))))
        .collect();
    let tx = db.unchecked_transaction()?;
    tx.execute_batch(
        "CREATE TABLE IF NOT EXISTS engine_references (
        reference_key TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES session_records(id));
        CREATE INDEX IF NOT EXISTS engine_references_session ON engine_references(session_id);
        CREATE INDEX IF NOT EXISTS sessions_creation_order ON session_records(created_at DESC,id DESC);",
    )?;
    for (id, key) in keys {
        tx.execute(
            "INSERT OR IGNORE INTO engine_references(reference_key,session_id) VALUES (?1,?2)",
            params![key, id],
        )?;
    }
    tx.execute_batch("PRAGMA user_version=5")?;
    tx.commit()?;
    Ok(())
}

impl Store {
    /// Candidates are already canonicalized by discovery's blocking reader. No
    /// managed paths are opened when serving a list or resolving its next page.
    pub fn unmanaged_sources(
        &self,
        sources: Vec<crate::discovery::OmpDiscoveredSession>,
    ) -> Result<Vec<crate::discovery::OmpDiscoveredSession>> {
        let db = self.db.lock();
        let mut query = db.prepare(
            "SELECT EXISTS(SELECT 1 FROM session_records WHERE id=?1)
            OR EXISTS(SELECT 1 FROM engine_references WHERE reference_key=?2)",
        )?;
        let mut result = Vec::new();
        for source in sources {
            let mapped: bool = query
                .query_row(params![source.id, canonical_key(&source.path)], |r| {
                    r.get(0)
                })?;
            if !mapped {
                result.push(source);
            }
        }
        Ok(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn record(store: &Store, id: &str) -> SessionRecord {
        SessionRecord {
            id: id.into(),
            host_id: store.host_id().unwrap(),
            cwd: "unused".into(),
            title: id.into(),
            metadata_revision: 1,
            created_at: Utc::now(),
            updated_at: Utc::now(),
            archived_at: None,
            engine_session_ref: None,
        }
    }
    #[test]
    fn conflicting_mapping_updates_roll_back_the_record_and_ownership() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        for id in ["one", "two"] {
            store
                .create_v2("client", id, id, record(&store, id))
                .unwrap();
        }
        let first = crate::workspace::display(&dir.path().join("first.jsonl"));
        let alias = crate::workspace::display(&dir.path().join(".").join("first.jsonl"));
        let second = crate::workspace::display(&dir.path().join("second.jsonl"));
        assert!(store.set_engine_ref("one", 1, &first).unwrap());
        assert!(store.set_engine_ref("two", 1, &alias).is_err());
        assert!(
            store
                .v2_session("two")
                .unwrap()
                .unwrap()
                .engine_session_ref
                .is_none()
        );
        assert!(store.set_engine_ref("two", 1, &second).unwrap());
        assert!(
            store
                .replace_missing_engine_ref_with_feedback("one", 2, &first, &second)
                .is_err()
        );
        assert_eq!(
            store
                .v2_session("one")
                .unwrap()
                .unwrap()
                .engine_session_ref
                .as_deref(),
            Some(first.as_str())
        );
        let mut third = record(&store, "three");
        third.engine_session_ref = Some(alias);
        assert!(store.create_v2("client", "three", "three", third).is_err());
        assert!(store.v2_session("three").unwrap().is_none());
    }
    #[test]
    fn v4_migration_preserves_legacy_duplicates_and_reserves_their_key() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        for id in ["one", "two", "three"] {
            store
                .create_v2("client", id, id, record(&store, id))
                .unwrap();
        }
        drop(store);
        let db = Connection::open(dir.path().join("pinkcollab.db")).unwrap();
        db.execute_batch("DROP TABLE engine_references; PRAGMA user_version=4;")
            .unwrap();
        let reference = crate::workspace::display(&dir.path().join("legacy.jsonl"));
        db.execute(
            "UPDATE session_records SET engine_session_ref=?1 WHERE id IN ('one','two')",
            [&reference],
        )
        .unwrap();
        drop(db);
        let store = Store::open(dir.path()).unwrap();
        assert_eq!(store.v2_sessions().unwrap().len(), 3);
        assert_eq!(
            store
                .db
                .lock()
                .query_row("SELECT COUNT(*) FROM engine_references", [], |r| r
                    .get::<_, i64>(0))
                .unwrap(),
            1
        );
        assert!(store.set_engine_ref("three", 1, &reference).is_err());
        drop(store);
        assert_eq!(
            Store::open(dir.path())
                .unwrap()
                .v2_sessions()
                .unwrap()
                .len(),
            3
        );
    }
    #[test]
    fn separate_connections_cannot_claim_the_same_reference() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        for id in ["one", "two"] {
            store
                .create_v2("client", id, id, record(&store, id))
                .unwrap();
        }
        let barrier = std::sync::Barrier::new(2);
        let reference = crate::workspace::display(&dir.path().join("shared.jsonl"));
        let wins = std::thread::scope(|scope| {
            let jobs: Vec<_> = ["one", "two"]
                .into_iter()
                .map(|id| {
                    let barrier = &barrier;
                    let reference = &reference;
                    let path = dir.path();
                    scope.spawn(move || {
                        let store = Store::open(path).unwrap();
                        barrier.wait();
                        store.set_engine_ref(id, 1, reference).is_ok()
                    })
                })
                .collect();
            jobs.into_iter()
                .map(|job| usize::from(job.join().unwrap()))
                .sum::<usize>()
        });
        assert_eq!(wins, 1);
        assert_eq!(
            store
                .v2_sessions()
                .unwrap()
                .iter()
                .filter(|s| s.engine_session_ref.is_some())
                .count(),
            1
        );
    }
}
