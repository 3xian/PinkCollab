//! Read-only adapter for OMP 18.4.2 file storage. No transcript data is persisted here.
use crate::{
    domain::SessionRecord,
    workspace::{self, Browser},
};
use anyhow::{Context, Result, ensure};
use chrono::{DateTime, Utc};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs,
    io::Read,
    path::{Path, PathBuf},
    time::{Duration, Instant},
};

mod layout;

const MAX_ENTRIES: usize = 8192;
const MAX_FILES: usize = 1024;
const PREFIX_BYTES: u64 = 16 * 1024;
const REFRESH: Duration = Duration::from_secs(15);

#[derive(Clone, Debug)]
pub struct OmpDiscoveredSession {
    pub id: String,
    pub omp_id: String,
    pub path: PathBuf,
    pub cwd: String,
    pub title: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}
impl OmpDiscoveredSession {
    // An ephemeral projection until Store::adopt commits this exact identity.
    pub fn record(&self, host_id: String) -> SessionRecord {
        SessionRecord {
            id: self.id.clone(),
            host_id,
            cwd: self.cwd.clone(),
            title: self.title.clone(),
            metadata_revision: 0,
            created_at: self.created_at,
            updated_at: self.updated_at,
            last_activity_at: self.updated_at,
            archived_at: None,
            engine_session_ref: Some(workspace::display(&self.path)),
        }
    }
}

pub struct Discovery {
    root: Option<PathBuf>,
    flat: bool,
    refreshed: Option<Instant>,
    sessions: Vec<OmpDiscoveredSession>,
}
impl Discovery {
    pub fn new(args: &[String]) -> Self {
        let layout::Layout { root, flat } = layout::Layout::from_process(args);
        Self {
            root,
            flat,
            refreshed: None,
            sessions: vec![],
        }
    }
    pub fn list(&mut self, browser: &Browser) -> Vec<OmpDiscoveredSession> {
        if self.refreshed.is_none_or(|t| t.elapsed() >= REFRESH) {
            self.sessions = self.scan(browser);
            self.refreshed = Some(Instant::now());
        }
        self.sessions
            .iter()
            .filter(|s| browser.validate(Path::new(&s.cwd)).is_ok() && s.path.is_file())
            .cloned()
            .collect()
    }
    /// Requests queued behind the same in-flight scan share its result.
    pub fn refresh_since(&mut self, requested: Instant, browser: &Browser) {
        if self.refreshed.is_none_or(|finished| finished < requested) {
            self.sessions = self.scan(browser);
            self.refreshed = Some(Instant::now());
        }
    }
    pub fn resolve(&mut self, id: &str, browser: &Browser) -> Result<Option<OmpDiscoveredSession>> {
        let Some(old) = self.list(browser).into_iter().find(|s| s.id == id) else {
            return Ok(None);
        };
        let root = self
            .root
            .as_ref()
            .context("history_unavailable")?
            .canonicalize()?;
        let current = read_session(&old.path, &root, browser)?;
        ensure!(
            current.id == old.id && current.cwd == old.cwd,
            "history_unavailable: session identity changed"
        );
        Ok(Some(current))
    }
    fn scan(&self, browser: &Browser) -> Vec<OmpDiscoveredSession> {
        let Some(root) = self.root.as_ref().and_then(|p| p.canonicalize().ok()) else {
            return vec![];
        };
        let mut budget = MAX_ENTRIES;
        let mut files = vec![];
        collect(&root, !self.flat, &mut budget, &mut files);
        files.sort_by_key(|(_, modified)| std::cmp::Reverse(*modified));
        let mut sessions = HashMap::new();
        for (path, _) in files.into_iter().take(MAX_FILES) {
            if let Ok(session) = read_session(&path, &root, browser) {
                // Copies bearing the same OMP ID are ambiguous; expose neither.
                sessions
                    .entry(session.id.clone())
                    .and_modify(|s| *s = None)
                    .or_insert(Some(session));
            }
        }
        sessions.into_values().flatten().collect()
    }
}
fn collect(
    dir: &Path,
    descend: bool,
    budget: &mut usize,
    files: &mut Vec<(PathBuf, std::time::SystemTime)>,
) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries {
        if *budget == 0 {
            break;
        }
        *budget -= 1;
        let Ok(entry) = entry else {
            continue;
        };
        let Ok(kind) = entry.file_type() else {
            continue;
        };
        if kind.is_symlink() {
            continue;
        }
        if kind.is_dir() && descend {
            collect(&entry.path(), false, budget, files);
        } else if kind.is_file()
            && entry.path().extension().is_some_and(|e| e == "jsonl")
            && let Ok(modified) = entry.metadata().and_then(|m| m.modified())
        {
            files.push((entry.path(), modified));
        }
    }
}
fn title(text: &str) -> String {
    text.split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .filter(|c| !c.is_control())
        .take(100)
        .collect()
}
pub fn read_session(path: &Path, root: &Path, browser: &Browser) -> Result<OmpDiscoveredSession> {
    let canonical = path.canonicalize().context("history_unavailable")?;
    ensure!(
        canonical.starts_with(root.canonicalize()?) && !fs::symlink_metadata(path)?.is_symlink(),
        "history_unavailable: unsafe reference"
    );
    let mut bytes = Vec::new();
    fs::File::open(&canonical)?
        .take(PREFIX_BYTES)
        .read_to_end(&mut bytes)?;
    let mut header = None;
    let mut name = String::new();
    let mut preview = String::new();
    let mut leading = bytes.split(|b| *b == b'\n');
    let first: Value = serde_json::from_slice(leading.next().unwrap_or_default())
        .context("history_unavailable: malformed leading record")?;
    let leading_header = if first["type"] == "title" {
        ensure!(
            first["v"] == 1
                && first["title"].is_string()
                && first["updatedAt"].is_string()
                && first["pad"].is_string()
                && first
                    .get("source")
                    .is_none_or(|v| v == "auto" || v == "user"),
            "history_unavailable: malformed title slot"
        );
        serde_json::from_slice::<Value>(leading.next().unwrap_or_default())
            .context("history_unavailable: malformed header")?
    } else {
        first
    };
    ensure!(
        leading_header["type"] == "session",
        "history_unavailable: missing leading header"
    );
    for line in bytes.split(|b| *b == b'\n') {
        let Ok(entry) = serde_json::from_slice::<Value>(line) else {
            continue;
        };
        match entry["type"].as_str() {
            Some("title") => name = title(entry["title"].as_str().unwrap_or("")),
            Some("session") if header.is_none() => header = Some(entry),
            Some("message") if preview.is_empty() && entry["message"]["role"] == "user" => {
                let content = &entry["message"]["content"];
                preview = title(&content.as_str().map(str::to_owned).unwrap_or_else(|| {
                    content
                        .as_array()
                        .map(|a| {
                            a.iter()
                                .filter_map(|v| v["text"].as_str())
                                .collect::<Vec<_>>()
                                .join(" ")
                        })
                        .unwrap_or_default()
                }));
            }
            _ => {}
        }
    }
    let header = header.context("history_unavailable: missing OMP header")?;
    ensure!(
        header["version"].as_u64() == Some(3),
        "history_unavailable: unsupported OMP session version"
    );
    let omp_id = header["id"]
        .as_str()
        .filter(|s| !s.is_empty() && s.len() <= 256)
        .context("history_unavailable: missing OMP identity")?
        .to_owned();
    let cwd = browser.validate(Path::new(header["cwd"].as_str().context("missing cwd")?))?;
    if let Some(extra) = header.get("additionalDirectories") {
        for dir in extra.as_array().context("invalid workspace metadata")? {
            browser.validate(Path::new(
                dir.as_str().context("invalid workspace metadata")?,
            ))?;
        }
    }
    if name.is_empty() {
        name = title(header["title"].as_str().unwrap_or(""));
    }
    if name.is_empty() {
        name = preview;
    }
    if name.is_empty() {
        name = "OMP conversation".into();
    }
    Ok(OmpDiscoveredSession {
        id: format!(
            "sess_{}",
            hex::encode(Sha256::digest(format!("omp:{omp_id}").as_bytes()))
        ),
        omp_id,
        path: canonical.clone(),
        cwd: workspace::display(&cwd),
        title: name,
        created_at: header["timestamp"]
            .as_str()
            .context("missing timestamp")?
            .parse()?,
        updated_at: fs::metadata(canonical)?.modified()?.into(),
    })
}

/// Revalidate an adopted mapping through the same reader used by discovery.
pub async fn validate_mapping(
    session: &SessionRecord,
    expected: &str,
    browser: std::sync::Arc<Browser>,
) -> Result<OmpDiscoveredSession> {
    let session = session.clone();
    let expected = expected.to_owned();
    tokio::task::spawn_blocking(move || {
        let path = Path::new(
            session
                .engine_session_ref
                .as_deref()
                .context("history_unavailable")?,
        );
        let source = read_session(
            path,
            path.parent().context("history_unavailable")?,
            &browser,
        )?;
        ensure!(
            source.omp_id == expected && source.cwd == session.cwd,
            "history_unavailable: OMP identity changed"
        );
        Ok(source)
    })
    .await?
}

/// `session lease` first shipped in 18.14.1. For a prerelease at the boundary,
/// attempt the probe rather than treating a possibly held session as unowned.
fn supports_session_lease(version: &str) -> Option<bool> {
    let version = version.strip_prefix("omp/").unwrap_or(version);
    let core = version.split(['-', '+']).next()?;
    let (major, rest) = core.split_once('.')?;
    let (minor, patch) = rest.split_once('.')?;
    let release: (u32, u32, u32) = (
        major.parse().ok()?,
        minor.parse().ok()?,
        patch.parse().ok()?,
    );
    Some(release >= (18, 14, 1))
}

/// A quiet transcript can still be owned by an idle OMP. Check before both adoption
/// and resume: a host process can reopen a previously adopted conversation.
pub(crate) async fn ensure_resumable(
    source: &OmpDiscoveredSession,
    executable: &str,
    args: &[String],
) -> Result<()> {
    ensure_quiet(&source.path).await?;
    let version = crate::omp::version(executable).await;
    if !supports_session_lease(&version)
        .context("history_unavailable: cannot identify OMP for the session lease check")?
    {
        return Ok(()); // Older CLIs need not recognize `session lease` as a command.
    }
    let mut command = std::process::Command::new(executable);
    if let Some(profile) = crate::omp::configured_profile(args) {
        command.args(["--profile", &profile]);
    }
    use std::process::Stdio;
    command
        .args(["session", "lease", "--json", "--", &source.omp_id])
        .current_dir(&source.cwd)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    let output = tokio::task::spawn_blocking(move || {
        crate::omp::finite_output(&mut command, Duration::from_secs(5), 8192)
    })
    .await?
    .context("history_unavailable: cannot check OMP session lease")?;
    #[derive(serde::Deserialize)]
    struct Leases {
        held: Vec<String>,
        unknown: Vec<String>,
    }
    let leases: Leases =
        serde_json::from_slice(&output).context("history_unavailable: invalid OMP lease report")?;
    ensure!(
        !leases.held.contains(&source.omp_id),
        "external_session_busy: close the external OMP session and retry"
    );
    ensure!(
        leases.unknown.is_empty(),
        "history_unavailable: OMP session ownership could not be confirmed"
    );
    Ok(())
}

/// OMP's publish lock is transient, not proof that an idle external runtime is absent.
/// Refuse observable writers; OMP itself enforces freshness under its publish lock.
pub fn check_writer(path: &Path) -> Result<()> {
    let name = path
        .file_name()
        .context("history_unavailable")?
        .to_string_lossy();
    ensure!(
        !path.with_file_name(format!(".{name}.lock")).exists(),
        "external_session_busy: close the external OMP session and retry"
    );
    Ok(())
}

/// Reject observable activity as well as a held lock. This is deliberately not a lease:
/// an idle external OMP can wake later, so users must close it before continuing here.
pub async fn ensure_quiet(path: &Path) -> Result<()> {
    check_writer(path)?;
    let before = tokio::fs::metadata(path)
        .await
        .context("history_unavailable")?;
    tokio::time::sleep(Duration::from_millis(200)).await;
    check_writer(path)?;
    let after = tokio::fs::metadata(path)
        .await
        .context("history_unavailable")?;
    ensure!(
        before.len() == after.len() && before.modified()? == after.modified()?,
        "external_session_busy: close the external OMP session and retry"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn lease_probe_distinguishes_the_first_supported_patch_and_fails_closed_on_unknown_versions() {
        assert_eq!(supports_session_lease("omp/18.14.0"), Some(false));
        assert_eq!(supports_session_lease("omp/18.14.1"), Some(true));
        assert_eq!(supports_session_lease("19.0.0-beta.1"), Some(true));
        assert_eq!(supports_session_lease("unavailable"), None);
    }

    #[test]
    fn refresh_expires_without_gateway_restart_and_filters_removed_workspaces() {
        let workspace = tempfile::tempdir().unwrap();
        let files = tempfile::tempdir().unwrap();
        let browser = Browser::new(&[workspace.path().into()]).unwrap();
        let mut discovery = Discovery::new(&[
            "--session-dir".into(),
            files.path().to_string_lossy().into_owned(),
        ]);
        assert!(discovery.list(&browser).is_empty());
        let header = json!({"type":"session","version":3,"id":"new","cwd":workspace.path(),"timestamp":"2026-01-01T00:00:00Z"});
        fs::write(files.path().join("new.jsonl"), format!("{header}\n")).unwrap();
        assert!(discovery.list(&browser).is_empty());
        discovery.refreshed = Instant::now().checked_sub(REFRESH);
        let discovered = discovery.list(&browser);
        assert_eq!(discovered.len(), 1);
        let other = tempfile::tempdir().unwrap();
        assert!(
            discovery
                .list(&Browser::new(&[other.path().into()]).unwrap())
                .is_empty()
        );
    }

    #[test]
    fn concurrent_force_requests_share_the_completed_scan() {
        let workspace = tempfile::tempdir().unwrap();
        let files = tempfile::tempdir().unwrap();
        let browser = Browser::new(&[workspace.path().into()]).unwrap();
        let mut discovery = Discovery::new(&[
            "--session-dir".into(),
            files.path().to_string_lossy().into_owned(),
        ]);
        let requested = Instant::now();
        discovery.refresh_since(requested, &browser);
        let header = json!({"type":"session","version":3,"id":"new","cwd":workspace.path(),"timestamp":"2026-01-01T00:00:00Z"});
        fs::write(files.path().join("new.jsonl"), format!("{header}\n")).unwrap();
        discovery.refresh_since(requested, &browser);
        assert!(discovery.list(&browser).is_empty());
        discovery.refresh_since(Instant::now(), &browser);
        assert_eq!(discovery.list(&browser).len(), 1);
    }

    #[tokio::test]
    async fn changing_file_is_an_explicit_writer_conflict() {
        let root = tempfile::tempdir().unwrap();
        let file = root.path().join("session.jsonl");
        fs::write(&file, "old").unwrap();
        let write = async {
            tokio::time::sleep(Duration::from_millis(50)).await;
            fs::write(&file, "new content").unwrap();
        };
        let (result, ()) = tokio::join!(ensure_quiet(&file), write);
        assert!(
            result
                .unwrap_err()
                .to_string()
                .contains("external_session_busy")
        );
    }
}
