use super::{Store, write_operation};
use crate::{domain::OperationRecord, history::FeedbackRecord};
use anyhow::{Result, ensure};
use rusqlite::{Connection, Transaction, TransactionBehavior, params};

/// Import the old authorities and retire them in the same schema transaction.
pub(super) fn migrate(db: &Connection) -> Result<()> {
    let tx = Transaction::new_unchecked(db, TransactionBehavior::Immediate)?;
    if tx.query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))? >= 10 {
        return Ok(());
    }
    tx.execute_batch(
        "CREATE TABLE IF NOT EXISTS local_timeline_records (
             session_id TEXT NOT NULL,
             reference TEXT NOT NULL,
             sequence INTEGER NOT NULL,
             record TEXT NOT NULL,
             PRIMARY KEY(session_id,sequence)
         );
         CREATE INDEX IF NOT EXISTS local_timeline_reference
         ON local_timeline_records(session_id,reference,sequence);",
    )?;
    let receipts = {
        let mut query = tx.prepare(
            "SELECT rowid,session_id,status,result FROM operation_records
             WHERE command_type='respond' AND result IS NOT NULL ORDER BY rowid",
        )?;
        query
            .query_map([], |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                ))
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?
    };
    for (rowid, session_id, status, raw) in receipts {
        let mut result: serde_json::Value = serde_json::from_str(&raw)?;
        if let Some(feedback) = result
            .as_object_mut()
            .and_then(|result| result.remove("feedback"))
        {
            if status == "succeeded" && !feedback.is_null() {
                let record: FeedbackRecord = serde_json::from_value(feedback)?;
                insert_record(&tx, &session_id, &record)?;
            }
            tx.execute(
                "UPDATE operation_records SET result=?1 WHERE rowid=?2",
                params![serde_json::to_string(&result)?, rowid],
            )?;
        }
    }
    let has_outputs: bool = tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='command_outputs')",
        [],
        |row| row.get(0),
    )?;
    if has_outputs {
        tx.execute_batch(
            "INSERT INTO local_timeline_records(session_id,reference,sequence,record)
             SELECT session_id,reference,sequence,record FROM command_outputs;
             DROP TABLE command_outputs;",
        )?;
    }
    tx.execute_batch("DROP INDEX IF EXISTS response_feedback; PRAGMA user_version=10;")?;
    tx.commit()?;
    Ok(())
}

fn insert_record(tx: &Transaction<'_>, session_id: &str, record: &FeedbackRecord) -> Result<()> {
    tx.execute(
        "INSERT INTO local_timeline_records(session_id,reference,sequence,record) VALUES (?1,?2,?3,?4)",
        params![session_id, record.reference, record.sequence, serde_json::to_string(record)?],
    )?;
    Ok(())
}

fn append_record(tx: &Transaction<'_>, session_id: &str, mut record: FeedbackRecord) -> Result<()> {
    record.sequence = tx.query_row(
        "SELECT COALESCE(MAX(sequence),0)+1 FROM local_timeline_records WHERE session_id=?1",
        [session_id],
        |row| row.get(0),
    )?;
    insert_record(tx, session_id, &record)
}

pub(super) fn remap_reference(
    tx: &Transaction<'_>,
    session_id: &str,
    old_reference: &str,
    reference: &str,
) -> Result<()> {
    let records = {
        let mut query = tx.prepare(
            "SELECT sequence,record FROM local_timeline_records WHERE session_id=?1 AND reference=?2",
        )?;
        query
            .query_map(params![session_id, old_reference], |row| {
                Ok((row.get::<_, u64>(0)?, row.get::<_, String>(1)?))
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?
    };
    for (sequence, raw) in records {
        let mut record: FeedbackRecord = serde_json::from_str(&raw)?;
        ensure!(
            record.anchor.is_empty(),
            "history_unavailable: missing transcript has non-root local timeline record"
        );
        record.reference = reference.to_owned();
        tx.execute(
            "UPDATE local_timeline_records SET reference=?1,record=?2 WHERE session_id=?3 AND sequence=?4",
            params![reference, serde_json::to_string(&record)?, session_id, sequence],
        )?;
    }
    Ok(())
}

impl Store {
    /// Commit transport-delivered feedback and its receipt together, assigning order by
    /// delivery commit rather than wall-clock timestamps or command admission order.
    pub(crate) fn complete_response(
        &self,
        mut receipt: OperationRecord,
        feedback: FeedbackRecord,
        input_request_id: &str,
    ) -> Result<Option<OperationRecord>> {
        let mut db = self.db.lock();
        let tx = db.transaction_with_behavior(TransactionBehavior::Immediate)?;
        receipt.status = "succeeded".into();
        receipt.updated_at = feedback.item.timestamp;
        receipt.error = None;
        receipt.result = Some(serde_json::json!({"inputRequestId": input_request_id}));
        append_record(&tx, &receipt.session_id, feedback)?;
        if !write_operation(&tx, &receipt)? {
            return Ok(None);
        }
        tx.commit()?;
        Ok(Some(receipt))
    }

    pub(crate) fn record_local_timeline(
        &self,
        session_id: &str,
        record: FeedbackRecord,
    ) -> Result<()> {
        let mut db = self.db.lock();
        let tx = db.transaction_with_behavior(TransactionBehavior::Immediate)?;
        append_record(&tx, session_id, record)?;
        tx.commit()?;
        Ok(())
    }

    /// The unified local ledger is the durable authority for feedback and command output,
    /// independent of the bounded recent-operations display window.
    pub fn feedback_records(
        &self,
        session_id: &str,
        reference: &str,
    ) -> Result<Vec<FeedbackRecord>> {
        let db = self.db.lock();
        let mut query = db.prepare(
            "SELECT record FROM local_timeline_records WHERE session_id=?1 AND reference=?2 ORDER BY sequence",
        )?;
        query
            .query_map(params![session_id, reference], |row| {
                row.get::<_, String>(0)
            })?
            .map(|row| Ok(serde_json::from_str(&row?)?))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{TimeZone, Utc};
    use serde_json::json;

    fn record(id: &str, kind: &str, sequence: u64) -> FeedbackRecord {
        serde_json::from_value(json!({
            "reference": "history.jsonl", "anchor": "root", "sequence": sequence,
            "item": {"id": id, "sourceId": id, "kind": kind, "text": id, "detail": "",
                     "timestamp": "2026-01-01T00:00:00Z"}
        }))
        .unwrap()
    }

    fn receipt(id: &str) -> OperationRecord {
        let now = Utc::now();
        OperationRecord {
            client_id: "client".into(),
            session_id: "session".into(),
            command_id: id.into(),
            command_type: "respond".into(),
            request_fingerprint: id.into(),
            runtime_generation: Some("generation".into()),
            status: "dispatching".into(),
            result: None,
            error: None,
            created_at: now,
            updated_at: now,
        }
    }

    fn legacy_store(dir: &std::path::Path, outputs: bool) -> Store {
        let store = Store::open(dir).unwrap();
        store
            .db
            .lock()
            .execute_batch(
                "DROP TABLE local_timeline_records;
             CREATE INDEX response_feedback ON operation_records(session_id)
             WHERE command_type='respond' AND status='succeeded';
             PRAGMA user_version=9;",
            )
            .unwrap();
        if outputs {
            store
                .db
                .lock()
                .execute_batch(
                    "CREATE TABLE command_outputs (
                    session_id TEXT NOT NULL, reference TEXT NOT NULL,
                    sequence INTEGER NOT NULL, record TEXT NOT NULL,
                    PRIMARY KEY(session_id,sequence));",
                )
                .unwrap();
        }
        store
    }

    #[test]
    fn migration_merges_legacy_authorities_once_and_removes_receipt_blobs() {
        let dir = tempfile::tempdir().unwrap();
        let store = legacy_store(dir.path(), true);
        let feedback = record("feedback", "feedback", 2);
        let mut response = receipt("response");
        response.status = "succeeded".into();
        response.result = Some(json!({"inputRequestId": "question", "feedback": feedback}));
        store.insert_operation(&response).unwrap();
        let mut failed = response.clone();
        failed.command_id = "failed".into();
        failed.status = "failed".into();
        store.insert_operation(&failed).unwrap();
        for output in [
            record("first", "command_output", 1),
            record("last", "command_output", 3),
        ] {
            store
                .db
                .lock()
                .execute(
                    "INSERT INTO command_outputs VALUES (?1,?2,?3,?4)",
                    params![
                        "session",
                        output.reference,
                        output.sequence,
                        serde_json::to_string(&output).unwrap()
                    ],
                )
                .unwrap();
        }
        drop(store);
        for _ in 0..2 {
            let store = Store::open(dir.path()).unwrap();
            let records = store.feedback_records("session", "history.jsonl").unwrap();
            assert_eq!(
                records
                    .iter()
                    .map(|r| r.item.id.as_str())
                    .collect::<Vec<_>>(),
                ["first", "feedback", "last"]
            );
            assert_eq!(
                records.iter().map(|r| r.sequence).collect::<Vec<_>>(),
                [1, 2, 3]
            );
            assert!(records.iter().all(|r| r.anchor == "root"));
            for id in ["response", "failed"] {
                assert_eq!(
                    store
                        .operation("client", "session", id)
                        .unwrap()
                        .unwrap()
                        .result,
                    Some(json!({"inputRequestId": "question"}))
                );
            }
            let db = store.db.lock();
            assert_eq!(
                db.query_row("PRAGMA user_version", [], |r| r.get::<_, i64>(0))
                    .unwrap(),
                10
            );
            assert_eq!(db.query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('command_outputs','response_feedback')",
                [], |r| r.get::<_, i64>(0),
            ).unwrap(), 0);
        }
    }

    #[test]
    fn migration_accepts_pre_output_schema_and_rolls_back_failed_imports() {
        let dir = tempfile::tempdir().unwrap();
        let store = legacy_store(dir.path(), false);
        let mut response = receipt("response");
        response.status = "succeeded".into();
        response.result = Some(json!({
            "inputRequestId": "question", "feedback": record("answer", "feedback", 1)
        }));
        store.insert_operation(&response).unwrap();
        drop(store);
        let store = Store::open(dir.path()).unwrap();
        assert_eq!(
            store
                .feedback_records("session", "history.jsonl")
                .unwrap()
                .len(),
            1
        );
        drop(store);

        let dir = tempfile::tempdir().unwrap();
        let store = legacy_store(dir.path(), true);
        store.insert_operation(&response).unwrap();
        let conflicting = record("output", "command_output", 1);
        store
            .db
            .lock()
            .execute(
                "INSERT INTO command_outputs VALUES ('session','history.jsonl',1,?1)",
                [serde_json::to_string(&conflicting).unwrap()],
            )
            .unwrap();
        drop(store);
        assert!(Store::open(dir.path()).is_err());
        let db = Connection::open(dir.path().join("pinkcollab.db")).unwrap();
        assert_eq!(
            db.query_row("PRAGMA user_version", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            9
        );
        assert_eq!(
            db.query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE name='local_timeline_records'",
                [],
                |r| r.get::<_, i64>(0),
            )
            .unwrap(),
            0
        );
        let result: String = db
            .query_row(
                "SELECT result FROM operation_records WHERE command_id='response'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&result).unwrap()["feedback"]["item"]["id"],
            "answer"
        );
        assert_eq!(
            db.query_row("SELECT COUNT(*) FROM command_outputs", [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            1
        );
    }

    #[test]
    fn response_append_rolls_back_when_receipt_is_missing_or_fails_to_save() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let response = receipt("response");
        assert!(
            store
                .complete_response(
                    response.clone(),
                    record("answer", "feedback", 0),
                    "question"
                )
                .unwrap()
                .is_none()
        );
        assert!(
            store
                .feedback_records("session", "history.jsonl")
                .unwrap()
                .is_empty()
        );
        store.insert_operation(&response).unwrap();
        store
            .db
            .lock()
            .execute_batch(
                "CREATE TRIGGER reject_completion BEFORE UPDATE ON operation_records
             WHEN NEW.status='succeeded' BEGIN SELECT RAISE(ABORT,'blocked'); END;",
            )
            .unwrap();
        assert!(
            store
                .complete_response(
                    response.clone(),
                    record("answer", "feedback", 0),
                    "question"
                )
                .is_err()
        );
        assert!(
            store
                .feedback_records("session", "history.jsonl")
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            store
                .operation("client", "session", "response")
                .unwrap()
                .unwrap()
                .status,
            "dispatching"
        );
        store
            .db
            .lock()
            .execute_batch("DROP TRIGGER reject_completion;")
            .unwrap();
        let saved = store
            .complete_response(response, record("answer", "feedback", 0), "question")
            .unwrap()
            .unwrap();
        assert_eq!(saved.status, "succeeded");
        assert_eq!(saved.result, Some(json!({"inputRequestId": "question"})));
        let records = store.feedback_records("session", "history.jsonl").unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].sequence, 1);
    }

    #[test]
    fn output_failure_receipts_are_generation_scoped_atomic_and_survive_exit_races() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        for (id, status, generation, command_type) in [
            ("dispatching", "dispatching", "generation", "prompt"),
            ("running", "running", "generation", "prompt"),
            ("exited", "outcome_unknown", "generation", "prompt"),
            ("accepted", "accepted", "generation", "prompt"),
            ("succeeded", "succeeded", "generation", "prompt"),
            ("failed", "failed", "generation", "prompt"),
            ("other-generation", "running", "other", "prompt"),
            ("respond", "dispatching", "generation", "respond"),
            ("stop", "dispatching", "generation", "stop_runtime"),
        ] {
            let mut receipt = receipt(id);
            receipt.command_type = command_type.into();
            receipt.status = status.into();
            receipt.runtime_generation = Some(generation.into());
            store.insert_operation(&receipt).unwrap();
        }
        store
            .db
            .lock()
            .execute_batch(
                "CREATE TRIGGER reject_failure BEFORE UPDATE ON operation_records
             WHEN NEW.command_id='running' BEGIN SELECT RAISE(ABORT,'blocked'); END;",
            )
            .unwrap();
        assert!(
            store
                .fail_generation_local_timeline("session", "generation", "disk full")
                .is_err()
        );
        assert_eq!(
            store
                .operation("client", "session", "dispatching")
                .unwrap()
                .unwrap()
                .status,
            "dispatching"
        );
        store
            .db
            .lock()
            .execute_batch("DROP TRIGGER reject_failure;")
            .unwrap();
        let failed = store
            .fail_generation_local_timeline("session", "generation", "disk full")
            .unwrap();
        assert_eq!(
            failed
                .iter()
                .map(|r| r.command_id.as_str())
                .collect::<Vec<_>>(),
            ["dispatching", "running", "exited"]
        );
        for receipt in failed {
            assert_eq!(receipt.status, "outcome_unknown");
            assert_eq!(
                receipt.error,
                Some(json!({"code": "output_persistence_failed", "message": "disk full"}))
            );
            assert_eq!(
                store
                    .operation("client", "session", &receipt.command_id)
                    .unwrap()
                    .unwrap()
                    .error,
                receipt.error
            );
            // An advance that captured controller state before the failure must not
            // overwrite the durable terminal fence after releasing that state lock.
            for status in ["running", "succeeded", "failed", "outcome_unknown"] {
                let mut stale = receipt.clone();
                stale.status = status.into();
                stale.error = None;
                assert!(!store.update_operation(&stale).unwrap());
                let saved = store
                    .operation("client", "session", &receipt.command_id)
                    .unwrap()
                    .unwrap();
                assert_eq!(saved.status, "outcome_unknown");
                assert_eq!(saved.error, receipt.error);
            }
        }
        for (id, status) in [
            ("accepted", "accepted"),
            ("succeeded", "succeeded"),
            ("failed", "failed"),
            ("other-generation", "running"),
            ("respond", "dispatching"),
            ("stop", "dispatching"),
        ] {
            assert_eq!(
                store
                    .operation("client", "session", id)
                    .unwrap()
                    .unwrap()
                    .status,
                status
            );
        }
        let mut stop = store
            .operation("client", "session", "stop")
            .unwrap()
            .unwrap();
        stop.status = "succeeded".into();
        assert!(store.update_operation(&stop).unwrap());
    }

    #[tokio::test]
    async fn mixed_records_use_one_commit_order_in_reconstructed_history_and_delete_together() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let path = dir.path().join("history.jsonl");
        let reference = path.to_string_lossy().into_owned();
        let local_record = |id, kind, sequence| {
            let mut record = record(id, kind, sequence);
            record.reference = reference.clone();
            record
        };
        store
            .record_local_timeline("session", local_record("first", "command_output", 99))
            .unwrap();
        let response = receipt("response");
        store.insert_operation(&response).unwrap();
        let mut feedback = local_record("second", "feedback", 99);
        feedback.item.timestamp = Utc.timestamp_opt(1, 0).single().unwrap();
        store
            .complete_response(response, feedback, "question")
            .unwrap()
            .unwrap();
        store
            .record_local_timeline("session", local_record("third", "command_output", 0))
            .unwrap();
        store
            .record_local_timeline("other-session", local_record("other", "command_output", 0))
            .unwrap();
        let mut other_reference = local_record("other-reference", "command_output", 0);
        other_reference.reference = "other.jsonl".into();
        store
            .record_local_timeline("session", other_reference)
            .unwrap();
        let records = store.feedback_records("session", &reference).unwrap();
        assert_eq!(
            records.iter().map(|r| r.sequence).collect::<Vec<_>>(),
            [1, 2, 3]
        );
        std::fs::write(&path, "{\"id\":\"root\",\"type\":\"session\"}\n").unwrap();
        let page = crate::history::history_page_with_anchor(
            crate::history::HistorySource {
                path: &path,
                allow_missing: false,
            },
            "session",
            None,
            100,
            None,
            None,
            &records,
        )
        .await
        .unwrap();
        let ids = page["items"]
            .as_array()
            .unwrap()
            .iter()
            .map(|item| item["id"].as_str().unwrap())
            .collect::<Vec<_>>();
        assert_eq!(ids, ["first", "second", "third"]);
        store.delete_session("session").unwrap();
        assert!(
            store
                .feedback_records("session", &reference)
                .unwrap()
                .is_empty()
        );
        assert!(
            store
                .feedback_records("session", "other.jsonl")
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            store
                .feedback_records("other-session", &reference)
                .unwrap()
                .len(),
            1
        );
    }
}
