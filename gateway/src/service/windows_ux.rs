//! Login-startup definition and compatibility checks for Windows.
use anyhow::{Result, ensure};

#[cfg(windows)]
pub fn legacy_command(
    command: &std::ffi::OsStr,
    binary: &std::path::Path,
    dir: &std::path::Path,
) -> Result<()> {
    use std::os::windows::ffi::{OsStrExt, OsStringExt};
    use windows_sys::Win32::{Foundation::LocalFree, UI::Shell::CommandLineToArgvW};
    let command: Vec<u16> = command.encode_wide().chain(Some(0)).collect();
    let mut count = 0;
    let argv = unsafe { CommandLineToArgvW(command.as_ptr(), &mut count) };
    ensure!(
        !argv.is_null(),
        "cannot parse legacy Gateway command: {}",
        std::io::Error::last_os_error()
    );
    // Windows owns one allocation for both the array and its argument strings.
    let args: Vec<std::ffi::OsString> = unsafe {
        std::slice::from_raw_parts(argv, count as usize)
            .iter()
            .map(|&arg| {
                let mut len = 0;
                while *arg.add(len) != 0 {
                    len += 1;
                }
                std::ffi::OsString::from_wide(std::slice::from_raw_parts(arg, len))
            })
            .collect()
    };
    unsafe {
        LocalFree(argv.cast());
    }
    let same_path = |arg: &std::ffi::OsStr, expected: &std::path::Path| match (
        std::path::Path::new(arg).canonicalize(),
        expected.canonicalize(),
    ) {
        (Ok(actual), Ok(expected)) => actual
            .to_string_lossy()
            .eq_ignore_ascii_case(&expected.to_string_lossy()),
        _ => false,
    };
    ensure!(
        args.len() == 4
            && args[1] == "--data-dir"
            && args[3] == "service-run"
            && same_path(&args[0], binary)
            && same_path(&args[2], dir),
        "The legacy Windows service uses another binary or data directory; manage it from its original installation first."
    );
    Ok(())
}

pub fn startup_script(command: &str) -> String {
    format!(
        "' PinkCollab managed login startup\r\nCreateObject(\"WScript.Shell\").Run \"{}\", 0, False\r\n",
        command.replace('"', "\"\"")
    )
}
pub fn startup_bytes(script: &str) -> Vec<u8> {
    // Windows Script Host detects Unicode scripts by the UTF-16 BOM.
    std::iter::once(0xfeffu16)
        .chain(script.encode_utf16())
        .flat_map(u16::to_le_bytes)
        .collect()
}

pub fn local_account(account: &str, identity: &str, computer: &str) -> Result<()> {
    let account = match account.strip_prefix(".\\") {
        Some(user) => format!("{computer}\\{user}"),
        None => account.to_owned(),
    };
    ensure!(
        !identity.trim().is_empty() && account.eq_ignore_ascii_case(identity),
        "The legacy PinkCollab Windows service belongs to another account. Migrate it as its original user; the existing configuration is safe."
    );
    Ok(())
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
    #[cfg(windows)]
    #[test]
    fn legacy_command_accepts_equivalent_quoting_but_rejects_other_installations() {
        let root = tempfile::tempdir().unwrap();
        let binary = root.path().join("gateway.exe");
        let dir = root.path().join("data");
        std::fs::write(&binary, b"fixture").unwrap();
        std::fs::create_dir(&dir).unwrap();
        let raw = format!(
            "{} --data-dir {} service-run",
            binary.display(),
            dir.display()
        );
        assert!(legacy_command(std::ffi::OsStr::new(&raw), &binary, &dir).is_ok());
        let quoted = super::super::windows_command(&binary, &dir.canonicalize().unwrap());
        assert!(legacy_command(std::ffi::OsStr::new(&quoted), &binary, &dir).is_ok());
        for invalid in [
            format!("{quoted} --extra"),
            quoted.replace("service-run", "serve"),
            quoted.replace("--data-dir", "--workspace"),
        ] {
            assert!(legacy_command(std::ffi::OsStr::new(&invalid), &binary, &dir).is_err());
        }
        assert!(legacy_command(std::ffi::OsStr::new(&quoted), &binary, root.path()).is_err());
        assert!(
            legacy_command(
                std::ffi::OsStr::new(&quoted),
                &root.path().join("other.exe"),
                &dir
            )
            .is_err()
        );
        let spaced = root.path().join("用户's data");
        std::fs::create_dir(&spaced).unwrap();
        let quoted = super::super::windows_command(&binary, &spaced);
        assert!(legacy_command(std::ffi::OsStr::new(&quoted), &binary, &spaced).is_ok());
    }
    #[test]
    fn startup_preserves_quoted_unicode_paths_without_a_console() {
        let command =
            r#""C:\Users\用户 Name\Gateway.exe" --data-dir "C:\User's data" background-start"#;
        let script = startup_script(command);
        assert_eq!(
            script.lines().nth(1).unwrap(),
            format!(
                "CreateObject(\"WScript.Shell\").Run \"{}\", 0, False",
                command.replace('"', "\"\"")
            )
        );
        let bytes = startup_bytes(&script);
        assert_eq!(&bytes[..2], &[0xff, 0xfe]);
        let utf16: Vec<u16> = bytes[2..]
            .chunks_exact(2)
            .map(|pair| u16::from_le_bytes([pair[0], pair[1]]))
            .collect();
        assert_eq!(String::from_utf16(&utf16).unwrap(), script);
    }
    #[test]
    fn legacy_local_account_matches_only_the_same_computer_user() {
        assert!(local_account(".\\Administrator", "GP-PC-1\\administrator", "GP-PC-1").is_ok());
        assert!(local_account(".\\Administrator", "DOMAIN\\Administrator", "GP-PC-1").is_err());
        assert!(local_account(".\\Other", "GP-PC-1\\Administrator", "GP-PC-1").is_err());
        assert!(local_account("LocalSystem", "GP-PC-1\\Administrator", "GP-PC-1").is_err());
    }
    #[test]
    fn environment_preserves_paths_and_overrides_conflicting_inputs() {
        let path = r"C:\Program Files\OMP;C:\Users\用户\bin";
        let profile = r"C:\Users\Test User";
        let json = serialize_environment(path, profile, [("PATH".into(), "old".into())]).unwrap();
        let values: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(values["PATH"], path);
        assert_eq!(values["USERPROFILE"], profile);
    }
}
