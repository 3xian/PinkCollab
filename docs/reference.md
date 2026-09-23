# CLI and configuration

Authoritative reference for the `pinkcollab` command and `config.yaml`. Defaults and rejections below match `gateway/src/main.rs`, `gateway/src/admin.rs`, and `gateway/src/config.rs` in this checkout, which is the same Gateway source as release v0.1.1.

This page is not an install guide. First-run steps are in [Get started](getting-started.md). Network setup is in [Deployment](deployment.md).

## Contents

- [Invocation](#invocation)
- [Commands](#commands)
- [Configuration](#configuration)
- [Validation](#validation)
- [Platform differences](#platform-differences)

## Invocation

```text
pinkcollab [--data-dir <path>] [<command>]
```

`--data-dir` is global. The default is the `.pinkcollab` directory under the home directory (`config::data_dir()`). If the home directory cannot be resolved, the default is `./.pinkcollab`.

Every command that reads or writes state must use the same OS user and the same data directory. A service started with a different `--data-dir` will not see the pairings and config you created in a terminal.

With no subcommand, the binary runs `serve`.

The npm package name is `pinkcollab`. The standalone and source-built executable is `pinkcollab-gateway` (`.exe` on Windows). Flags are the same.

## Commands

| Command | What it does |
| --- | --- |
| `init --workspace <dir>...` | One-time bootstrap. `--workspace` is required and repeatable. Each path must already exist, must be a directory, and is stored in canonical form. Fails if `config.yaml` already exists. Resolves `omp` on `PATH` to an absolute path and fails if it cannot. Creates the data directory, writes `config.yaml`, and opens the database. |
| `serve` | Loads config, opens the database, and serves until SIGINT or, on Unix, SIGTERM. Foreground. Default when no subcommand is given. A graceful shutdown stops OMP runtimes this Gateway started. |
| `status` | Preflight only. Prints config path, listen address, public URL, each workspace, the resolved OMP path and version, Tailscale state if the `tailscale` binary answers, whether the listen port can be bound, and paired-device and session counts. Prints `status: ready` and exits 0 when nothing is wrong. Exits non-zero while a problem remains. Run it while the Gateway is stopped: a running listener makes the port check fail. Tailscale being absent is printed, not by itself a failure. |
| `pair [--url <root>] [--qr <file>]` | Mints a single-use pairing token, prints pairing JSON on stdout, and renders a QR on stderr when stderr is a terminal. `--qr` also writes a PNG. `--url` overrides `public_url`. If neither is set, the command fails and tells you to pass `--url` or run `setup-funnel --pair`. It does not guess a Tailscale hostname and does not start HTTPS. The URL must be `https`, or `http` only for `127.0.0.1`, `localhost`, or `10.0.2.2`. |
| `clients` | Lists paired devices: `clientId`, escaped name, pairing time. Prints `No paired devices.` when the list is empty. |
| `revoke --client <clientId>` | Deletes that device's credential on the host. Fails when the id is unknown. Does not contact the phone. |
| `setup-funnel [--https 443] [--dry-run] [--tailscale <path>] [--pair]` | Publishes the loopback listener with Tailscale Funnel, writes `public_url`, and with `--pair` prints a pairing code. `--https` must be 443, 8443, or 10000. `--dry-run` prints the plan and does not change Tailscale or mint a code. `--tailscale` is the CLI path when `tailscale` is not on `PATH`. Requires the node `funnel` attribute; otherwise the Tailscale CLI stops with `Funnel not available; "funnel" node attribute not set`. |
| `service` | Windows only. Runs under the Service Control Manager. Starting it from a normal terminal fails with `service must be started by the Windows Service Control Manager`. |

### Pairing token

`pair` stores a SHA-256 hash of a 192-bit token (48 hex characters) with a 300-second expiry. A successful `POST /api/v1/pair` deletes that row in the same transaction, so the code works once. A wrong or expired token is rejected and does not become a credential. Generate a new code with `pair` after a failure or expiry.

### `status` is not a health check

`status` refusing to bind means the port is taken or the configuration is invalid. It does not query a running Gateway. Use the foreground log, or the service manager's logs, to see a process that is already up.

## Configuration

File: `<data-dir>/config.yaml`. The example in the repository is [`gateway/config.example.yaml`](../gateway/config.example.yaml). Unknown keys are rejected. `~` and `~/…` in `workspaces` expand when the file is loaded.

| Key | Default | Constraint |
| --- | --- | --- |
| `listen` | `127.0.0.1:8787` | Must be a loopback address. This is not the URL the phone dials. The Gateway does not terminate TLS. |
| `public_url` | empty | Optional until you pair without `--url`. When set, it must be an absolute `http` or `https` root URL: no userinfo, path, query, or fragment. Production phone URLs are `https`. `setup-funnel` rewrites this line and leaves other comments in place. |
| `name` | `COMPUTERNAME`, else `HOSTNAME`, else `my-host` | Label shown for this host in Android. |
| `workspaces` | empty until `init` | At least one directory at startup. Gateway browse and task-creation boundary only. Not an OS sandbox. |
| `omp` | `omp` in the template; `init` replaces it | Executable path. `init` stores the absolute path it resolved, which a service needs. A bare `omp` fails when the service `PATH` does not match your terminal. |
| `omp_args` | `[]` | Extra arguments placed after `--mode rpc-ui`. Must not set `--mode`, `--no-session`, `--session`, or the `--mode=` / `--session=` forms. |
| `max_sessions` | `8` | Integer from 1 to 100. See below. |
| `tls_cert`, `tls_key` | unset | Accepted by the parser only so old files fail clearly. Any config that still sets either key is rejected. Move TLS to Funnel, Serve, or a reverse proxy. |

<a id="max_sessions"></a>

### `max_sessions`

The limit counts sessions whose status is `starting`, plus sessions whose OMP process is still alive. It does not count a task merely because its status is `completed`.

A completed task whose process has not exited still occupies a slot. The slot is released when the process exits: after **Stop**, after a crash or normal exit, or when a start fails and the runtime is detached. A Gateway restart detaches every restored session, so those rows do not keep occupying slots, and it does not resume the processes.

Creating a task past the limit fails with `OMP runtime limit reached` (HTTP 422).

## Validation

Startup and `init` reject:

- an empty `workspaces` list
- `max_sessions` outside 1–100
- a non-loopback `listen` address
- a `public_url` that is not a bare root URL
- `omp_args` that override mode or session storage
- `tls_cert` or `tls_key`
- unknown fields
- a workspace path that cannot be canonicalized to a directory

`pair` additionally rejects a non-HTTPS URL unless the host is loopback or `10.0.2.2`.

## Platform differences

| Topic | Behavior |
| --- | --- |
| Windows executable lookup | `init` honors `PATHEXT` when resolving a bare `omp` name. |
| Windows service | `service` exists only in the Windows build. Install steps are in [deployment](deployment.md#windows). |
| Windows paths | Workspace display strips the `\\?\` and `\\?\UNC\` prefixes from canonical paths. |
| Unix permissions | The data directory is created mode `0700`, and new files mode `0600`, on Unix. Windows uses default ACLs. |
| Stop signals | Unix `serve` stops on SIGINT or SIGTERM. Elsewhere it stops on Ctrl+C. The Windows service also stops on Service Control stop or shutdown. |
| Standalone names | Release assets use `pinkcollab-gateway-<os>-<arch>` plus `.exe` on Windows. npm wraps those binaries behind the `pinkcollab` command. |

OMP itself is not shipped. `omp --version` must succeed for the user who runs the Gateway before tasks can start.
