use crate::model::Session;
use anyhow::{Context, Result, ensure};
use parking_lot::Mutex;
use rand::RngCore;
use rusqlite::{Connection, OpenFlags, params};
use sha2::{Digest, Sha256};
use std::path::Path;
pub struct Store {
    db: Mutex<Connection>,
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
        if !p.exists() {
            private_file(&p, &[])?;
        }
        let db = Connection::open(p)?;
        db.busy_timeout(std::time::Duration::from_secs(5))?;
        db.execute_batch("PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS identity (id INTEGER PRIMARY KEY CHECK(id=1),host_id TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS clients (id TEXT PRIMARY KEY,name TEXT NOT NULL,token_hash TEXT UNIQUE NOT NULL,created_at INTEGER);
CREATE TABLE IF NOT EXISTS pairing (token_hash TEXT PRIMARY KEY,expires_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY,metadata TEXT NOT NULL,session_file TEXT NOT NULL DEFAULT '');")?;
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
        Ok(Self { db: Mutex::new(db) })
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
        token.len() == 48
            && self
                .db
                .lock()
                .query_row(
                    "SELECT id FROM clients WHERE token_hash=?",
                    [hash(token)],
                    |r| r.get::<_, String>(0),
                )
                .is_ok()
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
        let sessions = db.query_row("SELECT COUNT(*) FROM sessions", [], |r| r.get::<_, i64>(0))?;
        Ok(Counts {
            clients: clients as usize,
            sessions: sessions as usize,
        })
    }
    pub fn save(&self, s: &Session) -> Result<()> {
        self.db.lock().execute("INSERT INTO sessions VALUES (?,?,?) ON CONFLICT(id) DO UPDATE SET metadata=excluded.metadata,session_file=excluded.session_file",params![s.id,serde_json::to_string(s)?,s.session_file])?;
        Ok(())
    }
    pub fn sessions(&self) -> Result<Vec<Session>> {
        let db = self.db.lock();
        let mut q = db.prepare("SELECT metadata,session_file FROM sessions")?;
        let rows = q.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
        rows.map(|r| {
            let (raw, file) = r?;
            let mut s: Session = serde_json::from_str(&raw).context("invalid session metadata")?;
            s.session_file = file;
            Ok(s)
        })
        .collect()
    }
    pub fn delete(&self, id: &str) -> Result<()> {
        self.db
            .lock()
            .execute("DELETE FROM sessions WHERE id=?", [id])?;
        Ok(())
    }
}
