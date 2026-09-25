use crate::v2_model::{OperationRecord, SessionRecord};
use anyhow::{Context, Result, ensure};
use chrono::{DateTime, Utc};
use parking_lot::Mutex;
use rand::RngCore;
use rusqlite::{Connection, OpenFlags, OptionalExtension, params};
use sha2::{Digest, Sha256};
use std::path::Path;
use std::{sync::Arc, time::Duration};
use tokio::sync::Semaphore;
pub struct Store {
    db: Mutex<Connection>,
    blocking_slots: Arc<Semaphore>,
}
/// A device that completed pairing. `created_at` is a Unix timestamp, absent on rows written
/// before the column existed.
pub struct Client {
    pub id: String,
    pub name: String,
    pub created_at: Option<i64>,
}
#[derive(Default)]
pub struct Counts {
    pub clients: usize,
    pub sessions: usize,
}
pub fn id(prefix: &str) -> String {
    let mut b = [0u8; 24];
    rand::rng().fill_bytes(&mut b);
    format!("{prefix}{}", hex::encode(b))
}
fn hash(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}
pub fn private_dir(path: &Path) -> Result<()> {
    std::fs::create_dir_all(path)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700))?;
    }
    Ok(())
}
pub fn private_file(path: &Path, bytes: &[u8]) -> Result<()> {
    use std::io::Write;
    let mut o = std::fs::OpenOptions::new();
    o.create(true).truncate(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        o.mode(0o600);
    }
    o.open(path)?.write_all(bytes)?;
    Ok(())
}
impl Store {
    pub fn open(dir: &Path) -> Result<Self> {
        private_dir(dir)?;
        let p = dir.join("pinkcollab.db");
        let existing = p.exists() && p.metadata()?.len() > 0;
        if !p.exists() {
            private_file(&p, &[])?;
        }
        let db = Connection::open(p)?;
        db.busy_timeout(std::time::Duration::from_secs(5))?;
        db.execute_batch("PRAGMA journal_mode=WAL;")?;
        let schema_version: i64 = db.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        ensure!(
            schema_version <= 2,
            "database schema is newer than this Gateway"
        );
        if existing && schema_version < 2 {
            // VACUUM INTO takes a consistent SQLite snapshot, including committed WAL content.
            // A plain copy of the main db file would omit uncheckpointed transactions.
            let backup = dir.join(format!("pinkcollab-pre-v2-{}.db", id("")));
            db.execute("VACUUM INTO ?1", [backup.to_string_lossy().as_ref()])
                .context("cannot create pre-v2 database backup")?;
        }
        db.execute_batch("
CREATE TABLE IF NOT EXISTS identity (id INTEGER PRIMARY KEY CHECK(id=1),host_id TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS clients (id TEXT PRIMARY KEY,name TEXT NOT NULL,token_hash TEXT UNIQUE NOT NULL,created_at INTEGER);
CREATE TABLE IF NOT EXISTS pairing (token_hash TEXT PRIMARY KEY,expires_at INTEGER NOT NULL);")?;
        // `clients.created_at` is newer than the first release; databases written before it still open.
        if db.query_row(
            "SELECT COUNT(*) FROM pragma_table_info('clients') WHERE name='created_at'",
            [],
            |r| r.get::<_, i64>(0),
        )? == 0
        {
            db.execute_batch("ALTER TABLE clients ADD COLUMN created_at INTEGER")?;
        }
        db.execute("INSERT OR IGNORE INTO identity VALUES (1,?)", [id("host_")])?;
        if schema_version < 2 {
            migrate_v2(&db)?;
        }
        Ok(Self {
            db: Mutex::new(db),
            blocking_slots: Arc::new(Semaphore::new(4)),
        })
    }
    /// Run SQLite work on a bounded blocking lane so a busy connection never parks a Tokio
    /// worker. The permit is held until the blocking work actually finishes, even if its caller
    /// is cancelled.
    pub async fn run<T, F>(self: &Arc<Self>, work: F) -> Result<T>
    where
        T: Send + 'static,
        F: FnOnce(&Store) -> Result<T> + Send + 'static,
    {
        let permit = tokio::time::timeout(
            Duration::from_secs(8),
            self.blocking_slots.clone().acquire_owned(),
        )
        .await
        .context("database work queue timed out")??;
        let store = self.clone();
        tokio::task::spawn_blocking(move || {
            let _permit = permit;
            work(&store)
        })
        .await
        .context("database worker stopped")?
    }
    pub fn host_id(&self) -> Result<String> {
        Ok(self
            .db
            .lock()
            .query_row("SELECT host_id FROM identity WHERE id=1", [], |r| r.get(0))?)
    }
    pub fn new_pairing(&self) -> Result<String> {
        let t = id("");
        let db = self.db.lock();
        db.execute(
            "DELETE FROM pairing WHERE expires_at<=?",
            [chrono::Utc::now().timestamp()],
        )?;
        db.execute(
            "INSERT INTO pairing (token_hash,expires_at) VALUES (?,?)",
            params![hash(&t), chrono::Utc::now().timestamp() + 300],
        )?;
        Ok(t)
    }
    pub fn pair(&self, token: &str, name: &str) -> Result<(String, String)> {
        let mut db = self.db.lock();
        let tx = db.transaction()?;
        ensure!(
            tx.execute(
                "DELETE FROM pairing WHERE token_hash=? AND expires_at>?",
                params![hash(token), chrono::Utc::now().timestamp()]
            )? == 1,
            "invalid or expired pairing token"
        );
        let client = id("client_");
        let credential = id("");
        tx.execute(
            "INSERT INTO clients (id,name,token_hash,created_at) VALUES (?,?,?,?)",
            params![
                client,
                name,
                hash(&credential),
                chrono::Utc::now().timestamp()
            ],
        )?;
        tx.commit()?;
        Ok((client, credential))
    }
    pub fn authenticate(&self, token: &str) -> bool {
        self.client_id(token).is_some()
    }
    pub fn client_id(&self, token: &str) -> Option<String> {
        (token.len() == 48)
            .then(|| {
                self.db
                    .lock()
                    .query_row(
                        "SELECT id FROM clients WHERE token_hash=?",
                        [hash(token)],
                        |r| r.get::<_, String>(0),
                    )
                    .ok()
            })
            .flatten()
    }
    /// Deletes a paired device and reports whether it existed.
    pub fn revoke(&self, id: &str) -> Result<bool> {
        Ok(self
            .db
            .lock()
            .execute("DELETE FROM clients WHERE id=?", [id])?
            == 1)
    }
    pub fn clients(&self) -> Result<Vec<Client>> {
        let db = self.db.lock();
        let mut query =
            db.prepare("SELECT id,name,created_at FROM clients ORDER BY created_at,id")?;
        let rows = query.query_map([], |r| {
            Ok(Client {
                id: r.get(0)?,
                name: r.get(1)?,
                created_at: r.get(2)?,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }
    /// Reads summary counts without creating or migrating the database.
    pub fn counts(dir: &Path) -> Result<Counts> {
        let path = dir.join("pinkcollab.db");
        if !path.exists() {
            return Ok(Counts::default());
        }
        let db = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
        let clients = db.query_row("SELECT COUNT(*) FROM clients", [], |r| r.get::<_, i64>(0))?;
        let has_v2: bool = db.query_row(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='session_records'",
            [],
            |r| r.get::<_, i64>(0),
        )? != 0;
        let has_legacy: bool = db.query_row(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='sessions'",
            [],
            |r| r.get::<_, i64>(0),
        )? != 0;
        let sessions = if has_v2 {
            db.query_row("SELECT COUNT(*) FROM session_records", [], |r| {
                r.get::<_, i64>(0)
            })?
        } else if has_legacy {
            db.query_row("SELECT COUNT(*) FROM sessions", [], |r| r.get::<_, i64>(0))?
        } else {
            0
        };
        Ok(Counts {
            clients: clients as usize,
            sessions: sessions as usize,
        })
    }
    /// Idempotency is scoped to the authenticated client and the host. The session row and
    /// create receipt commit together, so a lost HTTP response cannot create a second session.
    pub fn create_v2(
        &self,
        client_id: &str,
        command_id: &str,
        fingerprint: &str,
        session: SessionRecord,
    ) -> Result<(SessionRecord, bool)> {
        let mut db = self.db.lock();
        let tx = db.transaction()?;
        let existing: Option<(String, String)> = tx.query_row(
            "SELECT fingerprint,session_id FROM create_receipts WHERE client_id=?1 AND command_id=?2",
            params![client_id, command_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        ).optional()?;
        if let Some((old_fingerprint, session_id)) = existing {
            ensure!(old_fingerprint == fingerprint, "idempotency_conflict");
            let session =
                read_v2_session(&tx, &session_id)?.context("create receipt has no session")?;
            return Ok((session, true));
        }
        tx.execute(
            "INSERT INTO session_records (id,host_id,cwd,title,metadata_revision,created_at,updated_at,archived_at,engine_session_ref) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)",
            params![session.id, session.host_id, session.cwd, session.title, session.metadata_revision,
                session.created_at.to_rfc3339(), session.updated_at.to_rfc3339(),
                session.archived_at.map(|v| v.to_rfc3339()), session.engine_session_ref],
        )?;
        tx.execute(
            "INSERT INTO create_receipts (client_id,command_id,fingerprint,session_id) VALUES (?1,?2,?3,?4)",
            params![client_id, command_id, fingerprint, session.id],
        )?;
        tx.commit()?;
        Ok((session, false))
    }

    pub fn v2_sessions(&self) -> Result<Vec<SessionRecord>> {
        let db = self.db.lock();
        let mut query = db.prepare("SELECT id,host_id,cwd,title,metadata_revision,created_at,updated_at,archived_at,engine_session_ref FROM session_records ORDER BY updated_at DESC,id DESC")?;
        let rows = query.query_map([], session_columns)?;
        rows.map(|row| parse_session(row?)).collect()
    }

    /// Stable keyset pagination over creation order. Activity updates cannot move a row across
    /// pages while a client is walking the list.
    pub fn v2_sessions_page(
        &self,
        after: Option<(&str, &str)>,
        limit: usize,
    ) -> Result<(Vec<SessionRecord>, bool)> {
        let db = self.db.lock();
        let mut query = db.prepare(
            "SELECT id,host_id,cwd,title,metadata_revision,created_at,updated_at,archived_at,engine_session_ref
             FROM session_records
             WHERE (?1 IS NULL OR created_at < ?1 OR (created_at = ?1 AND id < ?2))
             ORDER BY created_at DESC,id DESC LIMIT ?3",
        )?;
        let (created_at, id) = after.map_or((None, None), |(created_at, id)| {
            (Some(created_at), Some(id))
        });
        let rows = query.query_map(params![created_at, id, (limit + 1) as i64], session_columns)?;
        let mut sessions = rows
            .map(|row| parse_session(row?))
            .collect::<Result<Vec<_>>>()?;
        let has_more = sessions.len() > limit;
        sessions.truncate(limit);
        Ok((sessions, has_more))
    }

    pub fn v2_session(&self, id: &str) -> Result<Option<SessionRecord>> {
        read_v2_session(&self.db.lock(), id)
    }

    pub fn set_engine_ref(
        &self,
        id: &str,
        expected_revision: i64,
        reference: &str,
    ) -> Result<bool> {
        Ok(self.db.lock().execute(
            "UPDATE session_records SET engine_session_ref=?1,metadata_revision=metadata_revision+1,updated_at=?2 WHERE id=?3 AND metadata_revision=?4 AND engine_session_ref IS NULL",
            params![reference, Utc::now().to_rfc3339(), id, expected_revision],
        )? == 1)
    }

    pub fn insert_operation(&self, op: &OperationRecord) -> Result<bool> {
        Ok(self.db.lock().execute(
            "INSERT OR IGNORE INTO operation_records (client_id,session_id,command_id,command_type,request_fingerprint,runtime_generation,status,result,error,created_at,updated_at) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
            params![op.client_id,op.session_id,op.command_id,op.command_type,op.request_fingerprint,
                op.runtime_generation,op.status,op.result.as_ref().map(serde_json::to_string).transpose()?,
                op.error.as_ref().map(serde_json::to_string).transpose()?,op.created_at.to_rfc3339(),op.updated_at.to_rfc3339()],
        )? == 1)
    }

    pub fn operation(
        &self,
        client_id: &str,
        session_id: &str,
        command_id: &str,
    ) -> Result<Option<OperationRecord>> {
        let db = self.db.lock();
        let raw = db.query_row(
            "SELECT command_type,request_fingerprint,runtime_generation,status,result,error,created_at,updated_at FROM operation_records WHERE client_id=?1 AND session_id=?2 AND command_id=?3",
            params![client_id,session_id,command_id],
            |r| Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,Option<String>>(2)?,r.get::<_,String>(3)?,r.get::<_,Option<String>>(4)?,r.get::<_,Option<String>>(5)?,r.get::<_,String>(6)?,r.get::<_,String>(7)?)),
        ).optional()?;
        raw.map(
            |(
                command_type,
                request_fingerprint,
                runtime_generation,
                status,
                result,
                error,
                created_at,
                updated_at,
            )| {
                Ok(OperationRecord {
                    command_id: command_id.into(),
                    client_id: client_id.into(),
                    session_id: session_id.into(),
                    command_type,
                    request_fingerprint,
                    runtime_generation,
                    status,
                    result: result.map(|v| serde_json::from_str(&v)).transpose()?,
                    error: error.map(|v| serde_json::from_str(&v)).transpose()?,
                    created_at: parse_time(&created_at)?,
                    updated_at: parse_time(&updated_at)?,
                })
            },
        )
        .transpose()
    }

    pub fn update_operation(&self, op: &OperationRecord) -> Result<bool> {
        let changed=self.db.lock().execute(
            "UPDATE operation_records SET runtime_generation=?1,status=?2,result=?3,error=?4,updated_at=?5 WHERE client_id=?6 AND session_id=?7 AND command_id=?8 AND (CASE ?2 WHEN 'dispatching' THEN status='accepted' WHEN 'running' THEN status IN ('accepted','dispatching') ELSE 1 END)",
            params![op.runtime_generation,op.status,op.result.as_ref().map(serde_json::to_string).transpose()?,
                op.error.as_ref().map(serde_json::to_string).transpose()?,op.updated_at.to_rfc3339(),
                op.client_id,op.session_id,op.command_id],
        )?;
        Ok(changed == 1)
    }
    pub fn bind_operation_generation(
        &self,
        op: &OperationRecord,
        generation: &str,
    ) -> Result<bool> {
        Ok(self.db.lock().execute(
            "UPDATE operation_records SET runtime_generation=?1,updated_at=?2 WHERE client_id=?3 AND session_id=?4 AND command_id=?5 AND status='dispatching'",
            params![generation,Utc::now().to_rfc3339(),op.client_id,op.session_id,op.command_id],
        )?==1)
    }

    pub fn recent_operations(
        &self,
        session_id: &str,
        limit: usize,
    ) -> Result<Vec<OperationRecord>> {
        let keys = {
            let db = self.db.lock();
            let mut query = db.prepare("SELECT client_id,command_id FROM operation_records WHERE session_id=?1 ORDER BY created_at DESC LIMIT ?2")?;
            query
                .query_map(params![session_id, limit.min(100) as i64], |r| {
                    Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
                })?
                .collect::<rusqlite::Result<Vec<_>>>()?
        };
        keys.into_iter()
            .map(|(client_id, command_id)| {
                self.operation(&client_id, session_id, &command_id)?
                    .context("operation disappeared")
            })
            .collect()
    }

    pub fn mark_generation_unknown(&self, session_id: &str, generation: &str) -> Result<()> {
        self.db.lock().execute(
            "UPDATE operation_records SET status='outcome_unknown',error='{\"code\":\"runtime_exited_before_result\"}',updated_at=?1 WHERE session_id=?2 AND runtime_generation=?3 AND status IN ('dispatching','running')",
            params![Utc::now().to_rfc3339(),session_id,generation],
        )?;
        Ok(())
    }

    /// A durable lease is deliberately conservative. An unclean Gateway exit leaves it behind,
    /// so a new process cannot silently become a second writer to the same OMP transcript.
    pub fn reserve_runtime(&self, session_id: &str, generation: &str) -> Result<bool> {
        Ok(self.db.lock().execute(
            "INSERT OR IGNORE INTO runtime_leases (session_id,generation,created_at) VALUES (?1,?2,?3)",
            params![session_id,generation,Utc::now().to_rfc3339()],
        )?==1)
    }
    pub fn runtime_leases(&self) -> Result<Vec<(String, String)>> {
        let db = self.db.lock();
        let mut query = db.prepare(
            "SELECT session_id,generation FROM runtime_leases ORDER BY created_at,session_id",
        )?;
        let rows = query.query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }
    pub fn release_runtime(&self, session_id: &str, generation: &str) -> Result<bool> {
        Ok(self.db.lock().execute(
            "DELETE FROM runtime_leases WHERE session_id=?1 AND generation=?2",
            params![session_id, generation],
        )? == 1)
    }

    pub fn recover_operations(&self) -> Result<()> {
        let db = self.db.lock();
        db.execute("UPDATE operation_records SET status='cancelled',error='{\"code\":\"gateway_restarted_before_dispatch\"}',updated_at=?1 WHERE status='accepted'",[Utc::now().to_rfc3339()])?;
        db.execute("UPDATE operation_records SET status='outcome_unknown',error='{\"code\":\"gateway_restarted_after_dispatch\"}',updated_at=?1 WHERE status IN ('dispatching','running')",[Utc::now().to_rfc3339()])?;
        Ok(())
    }
}

type SessionColumns = (
    String,
    String,
    String,
    String,
    i64,
    String,
    String,
    Option<String>,
    Option<String>,
);
fn session_columns(row: &rusqlite::Row<'_>) -> rusqlite::Result<SessionColumns> {
    Ok((
        row.get(0)?,
        row.get(1)?,
        row.get(2)?,
        row.get(3)?,
        row.get(4)?,
        row.get(5)?,
        row.get(6)?,
        row.get(7)?,
        row.get(8)?,
    ))
}
fn parse_time(raw: &str) -> Result<DateTime<Utc>> {
    Ok(DateTime::parse_from_rfc3339(raw)?.with_timezone(&Utc))
}
fn parse_session(
    (
        id,
        host_id,
        cwd,
        title,
        metadata_revision,
        created_at,
        updated_at,
        archived_at,
        engine_session_ref,
    ): SessionColumns,
) -> Result<SessionRecord> {
    Ok(SessionRecord {
        id,
        host_id,
        cwd,
        title,
        metadata_revision,
        created_at: parse_time(&created_at)?,
        updated_at: parse_time(&updated_at)?,
        archived_at: archived_at.map(|v| parse_time(&v)).transpose()?,
        engine_session_ref,
    })
}
fn read_v2_session(db: &Connection, id: &str) -> Result<Option<SessionRecord>> {
    db.query_row("SELECT id,host_id,cwd,title,metadata_revision,created_at,updated_at,archived_at,engine_session_ref FROM session_records WHERE id=?1",[id],session_columns).optional()?.map(parse_session).transpose()
}
fn migrate_v2(db: &Connection) -> Result<()> {
    db.execute_batch("BEGIN IMMEDIATE;")?;
    let result = (|| -> Result<()> {
        db.execute_batch("CREATE TABLE session_records (
            id TEXT PRIMARY KEY,host_id TEXT NOT NULL,cwd TEXT NOT NULL,title TEXT NOT NULL,
            metadata_revision INTEGER NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
            archived_at TEXT,engine_session_ref TEXT);
        CREATE TABLE create_receipts (
            client_id TEXT NOT NULL,command_id TEXT NOT NULL,fingerprint TEXT NOT NULL,
            session_id TEXT NOT NULL,PRIMARY KEY(client_id,command_id));
        CREATE TABLE operation_records (
            client_id TEXT NOT NULL,session_id TEXT NOT NULL,command_id TEXT NOT NULL,
            command_type TEXT NOT NULL,request_fingerprint TEXT NOT NULL,runtime_generation TEXT,
            status TEXT NOT NULL,result TEXT,error TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
            PRIMARY KEY(client_id,session_id,command_id));")?;
        db.execute_batch("CREATE TABLE runtime_leases (session_id TEXT PRIMARY KEY,generation TEXT NOT NULL,created_at TEXT NOT NULL);")?;
        let legacy_exists: bool = db.query_row(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='sessions'",
            [],
            |r| r.get::<_, i64>(0),
        )? != 0;
        if legacy_exists {
            let mut query = db.prepare("SELECT metadata,session_file FROM sessions")?;
            let rows =
                query.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
            for row in rows {
                let (metadata, session_file) = row?;
                let source: serde_json::Value =
                    serde_json::from_str(&metadata).context("invalid legacy session metadata")?;
                let required = |key: &str| -> Result<&str> {
                    source
                        .get(key)
                        .and_then(|v| v.as_str())
                        .with_context(|| format!("legacy session missing {key}"))
                };
                db.execute("INSERT INTO session_records (id,host_id,cwd,title,metadata_revision,created_at,updated_at,archived_at,engine_session_ref) VALUES (?1,?2,?3,?4,1,?5,?6,NULL,?7)",
                    params![required("id")?,required("hostId")?,required("cwd")?,required("title")?,parse_time(required("createdAt")?)?.to_rfc3339(),parse_time(required("updatedAt")?)?.to_rfc3339(),if session_file.is_empty(){None}else{Some(session_file.as_str())}])?;
            }
            db.execute_batch("DROP TABLE sessions;")?;
        }
        db.execute_batch("PRAGMA user_version=2;")?;
        Ok(())
    })();
    match result {
        Ok(()) => db.execute_batch("COMMIT;").map_err(Into::into),
        Err(err) => {
            let _ = db.execute_batch("ROLLBACK;");
            Err(err)
        }
    }
}
