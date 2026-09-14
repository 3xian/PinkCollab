use anyhow::{Context, Result, ensure};
use serde::Serialize;
use std::{
    path::{Path, PathBuf},
    time::Duration,
};
use tokio::process::Command;
#[derive(Clone, Debug, Serialize)]
pub struct Directory {
    pub name: String,
    pub path: String,
}
#[derive(Clone, Debug, Serialize)]
pub struct Git {
    pub branch: String,
    pub status: String,
}
#[derive(Clone, Debug, Serialize)]
pub struct Listing {
    pub path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parent: Option<String>,
    pub directories: Vec<Directory>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub git: Option<Git>,
}
pub struct Browser {
    roots: Vec<PathBuf>,
}
impl Browser {
    pub fn new(roots: &[PathBuf]) -> Result<Self> {
        let roots: Vec<PathBuf> = roots
            .iter()
            .map(|r| {
                r.canonicalize()
                    .with_context(|| format!("workspace unavailable: {}", r.display()))
            })
            .collect::<Result<_>>()?;
        ensure!(
            !roots.is_empty() && roots.iter().all(|p| p.is_dir()),
            "workspaces must be existing directories"
        );
        Ok(Self { roots })
    }
    pub fn roots(&self) -> Vec<Directory> {
        self.roots
            .iter()
            .map(|r| Directory {
                name: r.file_name().unwrap_or_default().to_string_lossy().into(),
                path: display(r),
            })
            .collect()
    }
    pub fn validate(&self, p: &Path) -> Result<PathBuf> {
        ensure!(p.is_absolute(), "absolute directory path required");
        let target = p.canonicalize().context("directory unavailable")?;
        ensure!(
            target.is_dir() && self.roots.iter().any(|r| target.starts_with(r)),
            "directory outside workspace allowlist"
        );
        Ok(target)
    }
    pub async fn list(&self, p: &Path) -> Result<Listing> {
        let p = self.validate(p)?;
        let mut entries = tokio::fs::read_dir(&p).await?;
        let mut directories = vec![];
        while let Some(e) = entries.next_entry().await? {
            if e.file_name().to_string_lossy().starts_with('.') {
                continue;
            }
            if self.validate(&e.path()).is_ok() {
                directories.push(Directory {
                    name: e.file_name().to_string_lossy().into(),
                    path: display(&e.path()),
                });
            }
        }
        directories.sort_by_key(|d| d.name.to_lowercase());
        let parent = p
            .parent()
            .and_then(|v| self.validate(v).ok())
            .filter(|v| v != &p)
            .map(|v| display(&v));
        let git = tokio::time::timeout(Duration::from_secs(2), async {
            let b = Command::new("git")
                .kill_on_drop(true)
                .arg("-C")
                .arg(&p)
                .args(["rev-parse", "--abbrev-ref", "HEAD"])
                .output()
                .await
                .ok()?;
            if !b.status.success() {
                return None;
            }
            let s = Command::new("git")
                .kill_on_drop(true)
                .arg("-C")
                .arg(&p)
                .args(["status", "--short", "--untracked-files=no"])
                .output()
                .await
                .ok()?;
            Some(Git {
                branch: String::from_utf8_lossy(&b.stdout).trim().into(),
                status: String::from_utf8_lossy(&s.stdout).trim().into(),
            })
        })
        .await
        .unwrap_or(None);
        Ok(Listing {
            path: display(&p),
            parent,
            directories,
            git,
        })
    }
}
pub fn display(p: &Path) -> String {
    let s = p.to_string_lossy();
    #[cfg(windows)]
    {
        s.strip_prefix("\\\\?\\UNC\\")
            .map(|v| format!("\\\\{v}"))
            .unwrap_or_else(|| s.strip_prefix("\\\\?\\").unwrap_or(&s).to_owned())
    }
    #[cfg(not(windows))]
    {
        s.into_owned()
    }
}
