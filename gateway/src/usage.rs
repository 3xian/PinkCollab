//! Host-wide provider quotas from OMP, independent of conversation runtimes.
use anyhow::{Context, Result};
use serde::Serialize;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    process::{Command, Stdio},
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::sync::Mutex;

const MAX_OUTPUT: u64 = 1024 * 1024;
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UsageSnapshot {
    pub generated_at: i64,
    pub accounts: Vec<UsageAccount>,
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UsageAccount {
    pub id: String,
    pub provider: String,
    pub account_label: String,
    pub plan: Option<String>,
    pub fetched_at: Option<i64>,
    pub status: String,
    pub limits: Vec<UsageLimit>,
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UsageLimit {
    pub id: String,
    pub label: String,
    pub model_id: Option<String>,
    pub tier: Option<String>,
    pub window_label: Option<String>,
    pub resets_at: Option<i64>,
    pub used_fraction: Option<f64>,
    pub used: Option<f64>,
    pub limit: Option<f64>,
    pub remaining: Option<f64>,
    pub unit: Option<String>,
    pub status: String,
}
pub struct UsageService {
    executable: String,
    profile: Option<String>,
    cache: Arc<Mutex<Option<(Instant, UsageSnapshot)>>>,
}
impl UsageService {
    pub fn new(executable: String, args: &[String]) -> Self {
        Self {
            executable,
            profile: crate::omp::configured_profile(args),
            cache: Arc::new(Mutex::new(None)),
        }
    }
    pub async fn read(&self) -> Result<UsageSnapshot> {
        let executable = self.executable.clone();
        let profile = self.profile.clone();
        let cache = self.cache.clone();
        // The detached owner retains the serialization slot through cleanup even
        // if the HTTP request is cancelled. Blocking pipe reads never run on Tokio.
        tokio::spawn(async move {
            let mut cache = cache.lock_owned().await;
            if let Some((at, value)) = &*cache
                && at.elapsed() < Duration::from_secs(30)
            {
                return Ok(value.clone());
            }
            let value = tokio::task::spawn_blocking(move || {
                let mut command = Command::new(executable);
                if let Some(profile) = profile {
                    command.args(["--profile", &profile]);
                }
                command
                    .args(["usage", "--json"])
                    .stdin(Stdio::null())
                    .stdout(Stdio::piped())
                    .stderr(Stdio::null());
                let output =
                    crate::omp::finite_output(&mut command, Duration::from_secs(30), MAX_OUTPUT)?;
                normalize(&serde_json::from_slice(&output).context("Invalid OMP usage JSON")?)
            })
            .await
            .context("OMP usage worker failed")??;
            *cache = Some((Instant::now(), value.clone()));
            Ok::<_, anyhow::Error>(value)
        })
        .await
        .context("OMP usage owner failed")?
    }
}
fn string(v: &Value, key: &str) -> Option<String> {
    v.get(key)?
        .as_str()
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
}
fn number(v: &Value, key: &str) -> Option<f64> {
    v.get(key)?.as_f64().filter(|n| n.is_finite())
}
fn masked(identity: &str) -> String {
    let prefix: String = identity.chars().take(2).collect();
    if identity.is_empty() {
        "Account".into()
    } else {
        format!("{prefix}***")
    }
}
fn account(v: &Value, status: &str, index: usize) -> UsageAccount {
    let metadata = v.get("metadata").unwrap_or(v);
    let provider = string(v, "provider").unwrap_or_else(|| "unknown".into());
    let identity = string(metadata, "email")
        .or_else(|| string(metadata, "accountId"))
        .or_else(|| string(metadata, "projectId"))
        .unwrap_or_else(|| format!("Account {}", index + 1));
    let key = format!(
        "{provider}:{}:{}",
        string(metadata, "accountId").unwrap_or_else(|| identity.clone()),
        string(metadata, "orgId").unwrap_or_default()
    );
    UsageAccount {
        id: hex::encode(Sha256::digest(key.as_bytes())),
        provider,
        account_label: masked(&identity),
        plan: string(metadata, "planType"),
        fetched_at: v.get("fetchedAt").and_then(Value::as_i64),
        status: status.into(),
        limits: vec![],
    }
}
fn normalize(raw: &Value) -> Result<UsageSnapshot> {
    let reports = raw
        .get("reports")
        .and_then(Value::as_array)
        .context("Missing usage reports")?;
    let mut accounts = Vec::new();
    for (index, report) in reports.iter().enumerate() {
        let mut row = account(report, "available", index);
        for (index, limit) in report
            .get("limits")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .enumerate()
        {
            let amount = &limit["amount"];
            let used_fraction = number(amount, "usedFraction")
                .or_else(|| number(amount, "remainingFraction").map(|n| 1.0 - n))
                .or_else(|| match (number(amount, "used"), number(amount, "limit")) {
                    (Some(used), Some(total)) if total > 0.0 => Some(used / total),
                    _ => None,
                });
            row.limits.push(UsageLimit {
                id: string(limit, "id").unwrap_or_else(|| format!("limit-{index}")),
                label: string(limit, "label").unwrap_or_else(|| "Quota".into()),
                model_id: string(&limit["scope"], "modelId"),
                tier: string(&limit["scope"], "tier"),
                window_label: string(&limit["window"], "label"),
                resets_at: limit["window"]["resetsAt"].as_i64(),
                used_fraction,
                used: number(amount, "used"),
                limit: number(amount, "limit"),
                remaining: number(amount, "remaining"),
                unit: string(amount, "unit"),
                status: string(limit, "status").unwrap_or_else(|| {
                    match used_fraction {
                        Some(n) if n >= 1.0 => "exhausted",
                        Some(n) if n >= 0.8 => "warning",
                        Some(_) => "ok",
                        None => "unknown",
                    }
                    .into()
                }),
            });
        }
        if row.limits.is_empty() {
            row.status = "unavailable".into();
        }
        accounts.push(row);
    }
    for (field, status) in [
        ("accountsWithoutUsage", "unavailable"),
        ("disabledCredentials", "disabled"),
    ] {
        for row in raw
            .get(field)
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            accounts.push(account(row, status, accounts.len()));
        }
    }
    Ok(UsageSnapshot {
        generated_at: raw
            .get("generatedAt")
            .and_then(Value::as_i64)
            .unwrap_or_else(|| chrono::Utc::now().timestamp_millis()),
        accounts,
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[cfg(windows)]
    #[test]
    fn finite_cmd_command_kills_job_workers_on_timeout_and_overflow() {
        for overflow in [false, true] {
            let dir = tempfile::tempdir().unwrap();
            let pid_file = dir.path().join("pid");
            let script = dir.path().join("omp.cmd");
            let pid_path = pid_file.to_string_lossy().replace('\'', "''");
            std::fs::write(&script, format!(
                "@echo off\r\npowershell.exe -NoProfile -Command \"$p = Start-Process powershell.exe -NoNewWindow -PassThru -ArgumentList '-NoProfile','-Command','Start-Sleep -Seconds 60'; [IO.File]::WriteAllText('{pid_path}', $p.Id.ToString()); {}; Start-Sleep -Seconds 60\"\r\n",
                if overflow { "[Console]::Write('123456789')" } else { "$null = 0" },
            )).unwrap();
            let mut command = Command::new("cmd.exe");
            command
                .arg("/C")
                .arg(&script)
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::null());
            let error =
                crate::omp::finite_output(&mut command, Duration::from_secs(10), 8).unwrap_err();
            assert!(error.to_string().contains(if overflow {
                "output too large"
            } else {
                "timed out"
            }));
            let pid = std::fs::read_to_string(pid_file)
                .unwrap()
                .trim()
                .parse::<u32>()
                .unwrap();
            let status = Command::new("powershell.exe")
                .args(["-NoProfile", "-Command", &format!(
                    "if (Get-Process -Id {pid} -ErrorAction SilentlyContinue) {{ exit 7 }} else {{ exit 0 }}"
                )]).status().unwrap();
            assert!(status.success(), "CMD worker survived containment cleanup");
        }
    }
    #[cfg(unix)]
    #[tokio::test]
    async fn cancelled_request_keeps_slot_until_process_tree_cleanup() {
        let dir = tempfile::tempdir().unwrap();
        let pid_file = dir.path().join("pid");
        let release = dir.path().join("release");
        let (_script_dir, executable) = script(&format!(
            "sleep 60 >/dev/null 2>&1 &\necho $! > '{}'\nwhile [ ! -f '{}' ]; do sleep 0.01; done\nprintf '%s' '{{\"reports\":[],\"accountsWithoutUsage\":[]}}'",
            pid_file.display(),
            release.display(),
        ));
        let service = Arc::new(UsageService::new(executable, &[]));
        let request = {
            let service = service.clone();
            tokio::spawn(async move { service.read().await })
        };
        tokio::time::timeout(Duration::from_secs(5), async {
            while !pid_file.exists() {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        request.abort();
        let _ = request.await;
        assert!(service.cache.try_lock().is_err());
        std::fs::write(&release, "").unwrap();
        let cache = tokio::time::timeout(Duration::from_secs(5), service.cache.lock())
            .await
            .unwrap();
        assert!(cache.is_some());
        let pid = std::fs::read_to_string(pid_file).unwrap();
        let output = Command::new("ps")
            .args(["-o", "stat=", "-p", pid.trim()])
            .output()
            .unwrap();
        let state = String::from_utf8(output.stdout).unwrap();
        assert!(
            state.trim().is_empty() || state.trim().starts_with('Z'),
            "{state}"
        );
    }
    #[cfg(unix)]
    fn script(body: &str) -> (tempfile::TempDir, String) {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("omp");
        std::fs::write(&path, format!("#!/bin/sh\n{body}\n")).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o700)).unwrap();
        (dir, path.to_string_lossy().into_owned())
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn usage_selects_last_profile_without_forwarding_runtime_options() {
        for args in [
            vec!["--profile=old", "--profile", "work", "--model", "ignored"],
            vec!["--profile", "old", "--profile=work", "--mode", "rpc-ui"],
            vec!["--profile", "default", "--model", "ignored"],
        ] {
            let expected = if args.contains(&"default") {
                "default"
            } else {
                "work"
            };
            let (_dir, executable) = script(&format!(
                "[ \"$#\" = 4 ] && [ \"$1\" = --profile ] && [ \"$2\" = {expected} ] && [ \"$3\" = usage ] && [ \"$4\" = --json ] || exit 9\nprintf '%s' '{{\"reports\":[],\"accountsWithoutUsage\":[]}}'"
            ));
            let args = args.into_iter().map(str::to_owned).collect::<Vec<_>>();
            let result = UsageService::new(executable, &args).read().await.unwrap();
            assert!(result.accounts.is_empty());
        }
    }

    #[cfg(unix)]
    #[test]
    fn finite_command_kills_descendants_on_timeout_and_output_overflow() {
        for overflow in [false, true] {
            let dir = tempfile::tempdir().unwrap();
            let pid = dir.path().join("pid");
            let body = format!(
                "sleep 60 &\necho $! > '{}'\n{}\nwait",
                pid.display(),
                if overflow { "printf '123456789'" } else { ":" },
            );
            let (_script_dir, executable) = script(&body);
            let mut command = Command::new(executable);
            command
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::null());
            let error =
                crate::omp::finite_output(&mut command, Duration::from_secs(10), 8).unwrap_err();
            assert!(error.to_string().contains(if overflow {
                "output too large"
            } else {
                "timed out"
            }));
            let pid = std::fs::read_to_string(pid)
                .unwrap()
                .trim()
                .parse::<i32>()
                .unwrap();
            let output = Command::new("ps")
                .args(["-o", "stat=", "-p", &pid.to_string()])
                .output()
                .unwrap();
            let state = String::from_utf8(output.stdout).unwrap();
            assert!(
                state.trim().is_empty() || state.trim().starts_with('Z'),
                "{state}"
            );
        }
    }
    #[test]
    fn quotas_keep_unknown_separate_from_zero_and_hide_identity() {
        let result = normalize(
            &json!({"reports":[{"provider":"test","metadata":{"email":"alice@example.com"},
            "limits":[{"id":"zero","amount":{"usedFraction":0.0}},
                      {"id":"unknown","amount":{}},
                      {"id":"absolute","amount":{"used":90,"limit":100},"scope":{"shared":true}}]}],
            "accountsWithoutUsage":[{"provider":"other","accountId":"secret-account"}]}),
        )
        .unwrap();
        assert_eq!(result.accounts[0].limits[0].used_fraction, Some(0.0));
        assert_eq!(result.accounts[0].limits[1].used_fraction, None);
        assert_eq!(result.accounts[0].limits[2].status, "warning");
        assert_eq!(result.accounts[1].status, "unavailable");
        let encoded = serde_json::to_string(&result).unwrap();
        assert!(!encoded.contains("alice@example.com"));
        assert!(!encoded.contains("secret-account"));
        assert!(!encoded.contains("\"shared\""));
    }
    #[test]
    fn invalid_shape_is_not_an_empty_success() {
        assert!(normalize(&json!({})).is_err());
    }
}
