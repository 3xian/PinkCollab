# Windows background options

Decision: continue Windows Service for this release. A per-user logon task is a
promising future desktop option, but requires explicit lifecycle tests before
adoption. Task Scheduler was evaluated only in documentation and is not implemented.

The comparison below is a design assessment, not evidence from an implemented task
backend. Microsoft documents [task security contexts](https://learn.microsoft.com/en-us/windows/win32/taskschd/security-contexts-for-running-tasks),
[task logon types](https://learn.microsoft.com/en-us/windows/win32/api/taskschd/ne-taskschd-task_logon_type),
and [service logon accounts](https://learn.microsoft.com/en-us/windows/win32/ad/service-logon-accounts).
Interactive-token tasks require an existing interactive user session; password
and S4U tasks have different credential and access semantics. In particular, S4U
has no network or encrypted-file access and is not an equivalent replacement.

| Concern | Current same-user Windows Service | Candidate interactive per-user logon task |
| --- | --- | --- |
| Startup timing | Automatic SCM startup | After user logon trigger |
| Login requirement | No desktop login required | Requires interactive login |
| Logout | Service can continue | Cannot promise continued operation without a session; test shutdown of OMP descendants |
| Reboot | Automatic startup before login | Waits for next user login |
| Credentials | Account password and service logon right | Interactive token avoids storing a password |
| UAC | Installation/update needs administrator | Own-user, normal-privilege registration can avoid elevation |
| User environment | Explicit environment snapshot | Must still capture and validate environment, not assume an interactive shell |
| PATH | Persisted absolute search paths | Same explicit contract needed |
| HOME/profile | Same-user profile paths serialized | Must resolve actual profile and HOME consistently |
| OMP credentials | Same identity and configured paths | Same identity; validate access in scheduled context |
| Provider credentials | Files accessible to service user; shell-only variables not implied | Must test file/credential-store access; shell-only variables still not implied |
| Workspace access | Allowlisted paths accessible to account; mapped drives unreliable | Allowlist unchanged; drive availability must be tested |
| Auto restart | Current service behavior; recovery policy needs separate validation | Configure restart conditions and prevent duplicate instances |
| Headless suitability | Appropriate | Poor: requires logged-in desktop |
| Upgrade | Stable binary, stop/stage/start, resumable update record | Must disable trigger, stop task and descendants, stage, restore trigger, restart |
| Uninstall | Stop service, remove registration; retain user data | Stop task, remove registration and triggers; retain user data |
| Security boundary | Same-user process, explicit credentials; no system-account fallback | Same-user normal token; never highest privileges by default |

Future adoption should test logout/reboot, crash restart, lock/unlock, expired
passwords, domain policy, Windows Hello accounts, network workspaces, credential
stores, task removal and interrupted upgrade recovery. It needs ownership and
single-instance checks compatible with existing data-dir ownership. Silent
migration could change host availability, so an explicit migration contract is
required. There is no experimental backend in this change.

PinkCollab Relay is intentionally not implemented in this change.
