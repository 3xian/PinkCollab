use anyhow::{Context, Result, ensure};
use serde::Serialize;
use std::path::{Path, PathBuf};
#[derive(Clone, Debug, Serialize)]
pub struct Directory {
    pub name: String,
    pub path: String,
}
#[derive(Clone, Debug, Serialize)]
pub struct Listing {
    pub path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parent: Option<String>,
    pub directories: Vec<Directory>,
}
pub struct Browser {
    roots: Vec<PathBuf>,
}
/// Canonicalizes, validates and de-duplicates workspace roots while preserving their order.
pub fn canonical_roots(roots: &[PathBuf]) -> Result<Vec<PathBuf>> {
    ensure!(!roots.is_empty(), "at least one workspace required");
    let mut canonical = Vec::with_capacity(roots.len());
    for root in roots {
        let root = root
            .canonicalize()
            .with_context(|| format!("workspace unavailable: {}", root.display()))?;
        ensure!(
            root.is_dir(),
            "workspace must be a directory: {}",
            root.display()
        );
        if !canonical.contains(&root) {
            canonical.push(root);
        }
    }
    Ok(canonical)
}
impl Browser {
    pub fn new(roots: &[PathBuf]) -> Result<Self> {
        Ok(Self {
            roots: canonical_roots(roots)?,
        })
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
            let file_type = e.file_type().await?;
            if !file_type.is_dir() && !file_type.is_symlink() {
                continue;
            }
            // A normal child directory is already contained by the canonical, allowlisted
            // parent. Only links need another canonicalization to prove that their target does
            // not escape the workspace. This keeps large folders from doing one blocking path
            // resolution per child on every tap.
            if file_type.is_symlink() && self.validate(&e.path()).is_err() {
                continue;
            }
            directories.push(Directory {
                name: e.file_name().to_string_lossy().into(),
                path: display(&e.path()),
            });
        }
        directories.sort_by_key(|d| d.name.to_lowercase());
        let parent = p
            .parent()
            .and_then(|v| self.validate(v).ok())
            .filter(|v| v != &p)
            .map(|v| display(&v));
        Ok(Listing {
            path: display(&p),
            parent,
            directories,
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
