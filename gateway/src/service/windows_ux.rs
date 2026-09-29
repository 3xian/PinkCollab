//! Platform-independent Windows validation and recovery copy.
use anyhow::{Result, ensure};
pub const ELEVATION_HELP: &str = "PinkCollab needs administrator permission to install its Windows background service.\n\nOpen an Administrator terminal as this same Windows user and run:\n\n  pinkcollab setup";
pub fn elevated(value: bool) -> Result<()> {
    ensure!(value, "{ELEVATION_HELP}");
    Ok(())
}
pub fn account(account: &str, identity: &str) -> Result<()> {
    ensure!(
        !identity.trim().is_empty()
            && ![
                "localsystem",
                "nt authority\\system",
                "nt authority\\networkservice",
                "nt authority\\localservice"
            ]
            .contains(&account.to_ascii_lowercase().as_str())
            && account.eq_ignore_ascii_case(identity),
        "PinkCollab service must run as the current user. Correct its Log On account in Windows Services, then run pinkcollab setup. Your existing configuration is safe."
    );
    Ok(())
}
// Exit protocol shared with windows_install.ps1; never used to decode SCM startup.
const INSTALL_WRONG_ACCOUNT: i32 = 10001;
const ERROR_CANCELLED: i32 = 1223;
pub fn install_script() -> String {
    format!(
        "$WrongAccountExit={INSTALL_WRONG_ACCOUNT}; $CancelledExit={ERROR_CANCELLED}\n{}",
        include_str!("windows_install.ps1")
    )
}
pub fn start_recovery(code: i32) -> &'static str {
    match code {
        5 => {
            "Windows denied permission to start the PinkCollab service. Check service access permissions in Windows Services and use an elevated terminal as the same user, then run pinkcollab service start."
        }
        1069 | 1385 | 1326 => {
            "Windows could not log on the Gateway service account. Check its password and Log on as a service / Deny log on as a service policies in Windows Services and Local Security Policy, then run pinkcollab service start."
        }
        _ => {
            "The Gateway service could not start. Inspect Windows Event Viewer and run pinkcollab doctor for details. The existing configuration is safe."
        }
    }
}
pub fn install_recovery(code: i32) -> &'static str {
    match code {
        5 => ELEVATION_HELP,
        INSTALL_WRONG_ACCOUNT => {
            "Choose the current Windows account for service credentials, then rerun pinkcollab setup. The existing configuration is safe."
        }
        ERROR_CANCELLED => {
            "Setup cancelled. No pairing token was created. The existing configuration is safe; rerun pinkcollab setup when ready."
        }
        1069 | 1385 => {
            "Windows denied the service login. Check this account's password and the 'Log on as a service' and 'Deny log on as a service' policies, then rerun pinkcollab setup. The existing PinkCollab configuration is safe and setup can be repeated."
        }
        1326 => {
            "Windows rejected the credentials. Use this same account's sign-in password (not a PIN), then rerun pinkcollab setup. The existing configuration is safe."
        }
        1073 => {
            "The service already exists. Inspect its Log On account in Windows Services and run pinkcollab doctor, then rerun pinkcollab setup. Do not delete your configuration."
        }
        _ => {
            "Windows service needs attention. Inspect Windows Event Viewer and run pinkcollab doctor, then rerun pinkcollab setup. The existing configuration is safe."
        }
    }
}
pub fn serialize_environment(
    path: &str,
    profile: &str,
    values: impl IntoIterator<Item = (String, String)>,
) -> Result<String> {
    let mut environment: std::collections::BTreeMap<String, String> = values.into_iter().collect();
    environment.insert("PATH".into(), path.into());
    environment.insert("USERPROFILE".into(), profile.into());
    Ok(serde_json::to_string(&environment)?)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn environment_preserves_spaces_quotes_and_unicode() {
        let path = r"C:\Program Files\OMP;C:\Users\用户\bin";
        let profile = r"C:\Users\Test User";
        let json = serialize_environment(path, profile, [("HOME".into(), profile.into())]).unwrap();
        let values: std::collections::BTreeMap<String, String> =
            serde_json::from_str(&json).unwrap();
        assert_eq!(values["PATH"], path);
        assert_eq!(values["USERPROFILE"], profile);
        assert_eq!(values["HOME"], profile);
    }
    #[test]
    fn installation_exit_protocol_and_start_errors_are_separate() {
        let script = install_script();
        assert!(script.contains(&format!("$WrongAccountExit={INSTALL_WRONG_ACCOUNT}")));
        assert!(script.contains("exit $WrongAccountExit"));
        assert!(script.contains("exit $CancelledExit"));
        assert!(install_recovery(INSTALL_WRONG_ACCOUNT).contains("current Windows account"));
        assert!(install_recovery(ERROR_CANCELLED).contains("cancelled"));
        assert!(start_recovery(5).contains("service access permissions"));
        assert!(!start_recovery(5).contains("install"));
        assert!(start_recovery(1069).contains("Log on as a service"));
    }
    #[test]
    fn explicit_environment_keys_override_conflicting_inputs() {
        let json = serialize_environment(
            "chosen path",
            "chosen profile",
            [
                ("PATH".into(), "old path".into()),
                ("USERPROFILE".into(), "old profile".into()),
            ],
        )
        .unwrap();
        let values: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(values["PATH"], "chosen path");
        assert_eq!(values["USERPROFILE"], "chosen profile");
    }
    #[test]
    fn identity_and_elevation() {
        assert!(account("DOMAIN\\User", "domain\\user").is_ok());
        assert!(account("DOMAIN\\Other", "domain\\user").is_err());
        assert!(account("LocalSystem", "LocalSystem").is_err());
        assert!(
            elevated(false)
                .unwrap_err()
                .to_string()
                .contains("same Windows user")
        );
        assert!(elevated(true).is_ok());
        for code in [5, 1069, 1385, 1326, 1073, 1] {
            assert!(install_recovery(code).contains("pinkcollab"));
        }
    }
}
