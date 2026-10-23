use super::*;

impl Store {
    pub(crate) fn session_delete_busy(&self, id: &str) -> Result<bool> {
        Ok(self.db.lock().query_row(
            "SELECT EXISTS(SELECT 1 FROM runtime_leases WHERE session_id=?1)
             OR EXISTS(SELECT 1 FROM operation_records WHERE session_id=?1
             AND status IN ('accepted','dispatching','running'))",
            [id],
            |r| r.get(0),
        )?)
    }

    /// Files are removed first; any failure leaves the mapping available for retry.
    /// Keep create receipts so a delayed create retry cannot resurrect a deleted task.
    pub(crate) fn delete_session(&self, id: &str) -> Result<()> {
        let mut db = self.db.lock();
        let tx = db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        for table in [
            "operation_records",
            "omp_adoptions",
            "engine_references",
            "session_records",
        ] {
            let column = if table == "session_records" {
                "id"
            } else {
                "session_id"
            };
            tx.execute(&format!("DELETE FROM {table} WHERE {column}=?1"), [id])?;
        }
        tx.commit()?;
        Ok(())
    }
}
