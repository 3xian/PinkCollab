use super::{Store, claim_reference, reference_key};
use anyhow::{Context, Result, ensure};
use chrono::Utc;
use rusqlite::{Connection, OptionalExtension, params};
use std::path::Path;

pub(super) fn migrate_file_expectation(db: &Connection) -> Result<()> {
    db.execute_batch("BEGIN IMMEDIATE;")?;
    let result = (|| -> Result<()> {
        if db.query_row(
            "SELECT COUNT(*) FROM pragma_table_info('session_records') WHERE name='history_file_expected'",
            [], |row| row.get::<_, i64>(0),
        )? == 0 {
            db.execute_batch("ALTER TABLE session_records ADD COLUMN history_file_expected INTEGER NOT NULL DEFAULT 0;")?;
        }
        db.execute_batch(
            "UPDATE session_records SET history_file_expected=MAX(history_file_expected,history_may_have_been_written);
             PRAGMA user_version=7;",
        )?;
        Ok(())
    })();
    match result {
        Ok(()) => db.execute_batch("COMMIT;").map_err(Into::into),
        Err(error) => {
            let _ = db.execute_batch("ROLLBACK;");
            Err(error)
        }
    }
}

impl Store {
    /// Feedback protects the mapping without claiming that OMP has created a transcript.
    pub fn prepare_response_history(
        &self,
        id: &str,
        reference: &str,
        file_exists: bool,
    ) -> Result<bool> {
        Ok(self.db.lock().execute(
            "UPDATE session_records SET history_may_have_been_written=1,
             history_file_expected=MAX(history_file_expected,?3)
             WHERE id=?1 AND engine_session_ref=?2 AND (?3 OR history_file_expected=0)",
            params![id, reference, file_exists],
        )? == 1)
    }

    /// Only the current mapping may grant missing-file permission. Seeing a file is irreversible.
    pub fn history_read_policy(
        &self,
        id: &str,
        reference: &str,
        file_exists: bool,
    ) -> Result<bool> {
        let db = self.db.lock();
        if file_exists {
            ensure!(db.execute(
                "UPDATE session_records SET history_file_expected=1 WHERE id=?1 AND engine_session_ref=?2",
                params![id, reference],
            )? == 1, "history_unavailable: session mapping changed");
            return Ok(false);
        }
        let expected = db.query_row(
            "SELECT history_file_expected FROM session_records WHERE id=?1 AND engine_session_ref=?2",
            params![id, reference],
            |row| row.get::<_, bool>(0),
        ).optional()?.context("history_unavailable: session mapping changed")?;
        Ok(!expected)
    }

    /// Replace an absent, explicitly allowed transcript and move its durable root feedback
    /// in the same transaction. Written or observed transcripts are never replaceable here.
    pub fn replace_missing_engine_ref_with_feedback(
        &self,
        id: &str,
        expected_revision: i64,
        old_reference: &str,
        reference: &str,
    ) -> Result<bool> {
        let key = reference_key(Path::new(reference));
        let mut db = self.db.lock();
        let tx = db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let changed = tx.execute(
            "UPDATE session_records SET engine_session_ref=?1,metadata_revision=metadata_revision+1,updated_at=?2
             WHERE id=?3 AND metadata_revision=?4 AND engine_session_ref=?5 AND history_file_expected=0",
            params![reference, Utc::now().to_rfc3339(), id, expected_revision, old_reference],
        )? == 1;
        if changed {
            tx.execute("DELETE FROM engine_references WHERE session_id=?1", [id])?;
            claim_reference(&tx, id, &key)?;
            let results = {
                let mut query = tx.prepare(
                    "SELECT client_id,command_id,result FROM operation_records
                     WHERE session_id=?1 AND command_type='respond' AND status='succeeded' AND result IS NOT NULL",
                )?;
                query
                    .query_map([id], |row| {
                        Ok((
                            row.get::<_, String>(0)?,
                            row.get::<_, String>(1)?,
                            row.get::<_, String>(2)?,
                        ))
                    })?
                    .collect::<rusqlite::Result<Vec<_>>>()?
            };
            for (client_id, command_id, raw) in results {
                let mut result: serde_json::Value = serde_json::from_str(&raw)?;
                if result["feedback"]["reference"] == old_reference {
                    ensure!(
                        result["feedback"]["anchor"] == "",
                        "history_unavailable: missing transcript has non-root feedback"
                    );
                    result["feedback"]["reference"] = reference.into();
                    tx.execute(
                        "UPDATE operation_records SET result=?1 WHERE client_id=?2 AND session_id=?3 AND command_id=?4",
                        params![serde_json::to_string(&result)?, client_id, id, command_id],
                    )?;
                }
            }
        }
        tx.commit()?;
        Ok(changed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::SessionRecord;

    #[test]
    fn feedback_only_mapping_permission_is_persisted_and_monotonic() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let now = Utc::now();
        store
            .create_v2(
                "client",
                "create",
                "fingerprint",
                SessionRecord {
                    id: "session".into(),
                    host_id: store.host_id().unwrap(),
                    cwd: dir.path().to_string_lossy().into_owned(),
                    title: String::new(),
                    metadata_revision: 0,
                    created_at: now,
                    updated_at: now,
                    archived_at: None,
                    engine_session_ref: Some("old.jsonl".into()),
                },
            )
            .unwrap();
        assert!(
            store
                .history_read_policy("session", "old.jsonl", false)
                .unwrap()
        );
        assert!(
            store
                .prepare_response_history("session", "old.jsonl", false)
                .unwrap()
        );
        assert!(
            !store
                .reference_is_unwritten("session", "old.jsonl")
                .unwrap()
        );
        assert!(
            store
                .history_read_policy("session", "other.jsonl", false)
                .is_err()
        );
        assert!(
            !store
                .prepare_response_history("session", "other.jsonl", false)
                .unwrap()
        );
        drop(store);
        let store = Store::open(dir.path()).unwrap();
        assert!(
            store
                .history_read_policy("session", "old.jsonl", false)
                .unwrap()
        );
        assert!(
            !store
                .history_read_policy("session", "old.jsonl", true)
                .unwrap()
        );
        assert!(
            !store
                .history_read_policy("session", "old.jsonl", false)
                .unwrap()
        );
        assert!(
            !store
                .prepare_response_history("session", "old.jsonl", false)
                .unwrap()
        );
        assert!(
            !store
                .replace_missing_engine_ref_with_feedback("session", 0, "old.jsonl", "new.jsonl")
                .unwrap()
        );
    }

    #[test]
    fn missing_reference_replacement_moves_root_feedback_atomically() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let db = store.db.lock();
        db.execute_batch(
            "INSERT INTO session_records (id,host_id,cwd,title,metadata_revision,created_at,updated_at,engine_session_ref,history_may_have_been_written)
             VALUES ('session','host','cwd','',0,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','old.jsonl',1);
             INSERT INTO operation_records (client_id,session_id,command_id,command_type,request_fingerprint,status,result,created_at,updated_at)
             VALUES ('client','session','response','respond','fingerprint','succeeded',
             '{\"feedback\":{\"reference\":\"old.jsonl\",\"anchor\":\"\",\"item\":{\"id\":\"answer\"}}}',
             '2026-01-01T00:00:00Z','2026-01-01T00:00:00Z');",
        ).unwrap();
        drop(db);
        assert!(
            !store
                .replace_missing_engine_ref_with_feedback("session", 1, "old.jsonl", "new.jsonl")
                .unwrap()
        );
        assert!(
            store
                .replace_missing_engine_ref_with_feedback("session", 0, "old.jsonl", "new.jsonl")
                .unwrap()
        );
        assert!(
            !store
                .reference_is_unwritten("session", "new.jsonl")
                .unwrap()
        );
        let result: String = store
            .db
            .lock()
            .query_row(
                "SELECT result FROM operation_records WHERE command_id='response'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        let result: serde_json::Value = serde_json::from_str(&result).unwrap();
        assert_eq!(result["feedback"]["reference"], "new.jsonl");
        assert_eq!(result["feedback"]["item"]["id"], "answer");
        assert!(
            store
                .history_read_policy("session", "old.jsonl", false)
                .is_err()
        );
        assert!(
            store
                .history_read_policy("session", "new.jsonl", false)
                .unwrap()
        );
    }

    #[test]
    fn restart_after_response_dispatch_requires_history_instead_of_replacing_an_uncertain_answer() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        store.db.lock().execute_batch(
            "INSERT INTO session_records (id,host_id,cwd,title,metadata_revision,created_at,updated_at,engine_session_ref,history_may_have_been_written)
             VALUES ('session','host','cwd','',0,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','old.jsonl',1);
             INSERT INTO operation_records (client_id,session_id,command_id,command_type,request_fingerprint,status,created_at,updated_at)
             VALUES ('client','session','response','respond','fingerprint','dispatching',
             '2026-01-01T00:00:00Z','2026-01-01T00:00:00Z');",
        ).unwrap();
        store.recover_operations().unwrap();
        assert_eq!(
            store
                .operation("client", "session", "response")
                .unwrap()
                .unwrap()
                .status,
            "outcome_unknown"
        );
        assert!(
            !store
                .history_read_policy("session", "old.jsonl", false)
                .unwrap()
        );
        assert!(
            !store
                .replace_missing_engine_ref_with_feedback("session", 0, "old.jsonl", "new.jsonl")
                .unwrap()
        );
    }

    #[test]
    fn migration_protects_every_possibly_written_history() {
        let db = Connection::open_in_memory().unwrap();
        db.execute_batch(
            "CREATE TABLE session_records (id TEXT,history_may_have_been_written INTEGER NOT NULL);
             INSERT INTO session_records VALUES ('empty',0),('possibly-written',1);",
        )
        .unwrap();
        migrate_file_expectation(&db).unwrap();
        let expected: i64 = db
            .query_row(
                "SELECT history_file_expected FROM session_records WHERE id='possibly-written'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(expected, 1);
        let empty: i64 = db
            .query_row(
                "SELECT history_file_expected FROM session_records WHERE id='empty'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(empty, 0);
    }
}
