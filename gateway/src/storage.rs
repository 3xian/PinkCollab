use crate::model::Session;
use anyhow::{Context, Result, ensure};
use rand::RngCore;
use rusqlite::{Connection, params};
use sha2::{Digest, Sha256};
use std::{path::Path, sync::Mutex};
pub struct Store {
    db: Mutex<Connection>,
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
CREATE TABLE IF NOT EXISTS clients (id TEXT PRIMARY KEY,name TEXT NOT NULL,token_hash TEXT UNIQUE NOT NULL);
CREATE TABLE IF NOT EXISTS pairing (token_hash TEXT PRIMARY KEY,expires_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY,metadata TEXT NOT NULL,session_file TEXT NOT NULL DEFAULT '');")?;
        db.execute("INSERT OR IGNORE INTO identity VALUES (1,?)", [id("host_")])?;
        Ok(Self { db: Mutex::new(db) })
    }
    pub fn host_id(&self) -> Result<String> {
        Ok(self.db.lock().unwrap().query_row(
            "SELECT host_id FROM identity WHERE id=1",
            [],
            |r| r.get(0),
        )?)
    }
    pub fn new_pairing(&self) -> Result<String> {
        let t = id("");
        let db = self.db.lock().unwrap();
        db.execute(
            "DELETE FROM pairing WHERE expires_at<=?",
            [chrono::Utc::now().timestamp()],
        )?;
        db.execute(
            "INSERT INTO pairing VALUES (?,?)",
            params![hash(&t), chrono::Utc::now().timestamp() + 300],
        )?;
        Ok(t)
    }
    pub fn pair(&self, token: &str, name: &str) -> Result<(String, String)> {
        let mut db = self.db.lock().unwrap();
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
            "INSERT INTO clients VALUES (?,?,?)",
            params![client, name, hash(&credential)],
        )?;
        tx.commit()?;
        Ok((client, credential))
    }
    pub fn authenticate(&self, token: &str) -> bool {
        token.len() == 48
            && self
                .db
                .lock()
                .unwrap()
                .query_row(
                    "SELECT id FROM clients WHERE token_hash=?",
                    [hash(token)],
                    |r| r.get::<_, String>(0),
                )
                .is_ok()
    }
    pub fn revoke(&self, id: &str) -> Result<()> {
        self.db
            .lock()
            .unwrap()
            .execute("DELETE FROM clients WHERE id=?", [id])?;
        Ok(())
    }
    pub fn save(&self, s: &Session) -> Result<()> {
        self.db.lock().unwrap().execute("INSERT INTO sessions VALUES (?,?,?) ON CONFLICT(id) DO UPDATE SET metadata=excluded.metadata,session_file=excluded.session_file",params![s.id,serde_json::to_string(s)?,s.session_file])?;
        Ok(())
    }
    pub fn sessions(&self) -> Result<Vec<Session>> {
        let db = self.db.lock().unwrap();
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
            .unwrap()
            .execute("DELETE FROM sessions WHERE id=?", [id])?;
        Ok(())
    }
}
