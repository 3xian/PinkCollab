# Development

Build Android and Gateway from the same checkout. Requirements: Rust 1.89+, JDK 17, Android SDK 36, and Node.js 18+ for npm and website checks. OMP must be installed separately for local sessions and real-OMP tests.

## Run the Gateway during development

From `gateway/`, with OMP on `PATH`:

```sh
cargo run -- init --workspace /absolute/path/to/projects  # once
cargo run -- serve
```

`serve` runs in the foreground. Put `--` before Gateway arguments (`cargo run -- pair`). Data defaults to `~/.pinkcollab`; use `--data-dir <path>` after `--` on every command to isolate development data. HTTPS and pairing: [Deployment](deployment.md#tailscale-funnel).

## Build the Gateway

From `gateway/`:

```sh
cargo build --release --locked --bin pinkcollab-gateway
```

Output: `target/release/pinkcollab-gateway` (`.exe` on Windows).

## Build Android

From `android/`:

```sh
sh ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, run `cmd.exe /c gradlew.bat :app:assembleDebug`; use the same `cmd.exe /c gradlew.bat` prefix for other Gradle commands below. Set `sdk.dir` in `android/local.properties` or `ANDROID_HOME`.

Debug and production APKs share an application ID but use different signing keys. Switching between them requires uninstalling and loses phone-side data; keep the signed production app when validating upgrades. The separate `releaseTest` build below can coexist with it. See [release signing](npm-release.md#install-the-android-signing-key-on-a-build-machine).

## Validation

### Standard checks

From `gateway/`:

```sh
cargo fmt --check
cargo clippy --all-targets --all-features --locked -- -D warnings
cargo test --all-features --locked
```

From `android/`:

```sh
sh ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

From the repository root:

```sh
node npm/scripts/check-release.mjs
npm test --prefix npm/pinkcollab
npm pack ./npm/pinkcollab --dry-run
node .github/actions/check-website/check-site.mjs
```

API changes must match the [protocol](protocol.md). Use Conventional Commits and keep documentation in its existing topic structure. Publishing follows the separate [release handbook](npm-release.md).

### Gateway regressions

Run a focused integration suite with `cargo test --all-features --locked --test <suite>` from `gateway/`.

| Area | Suites and coverage |
| --- | --- |
| History and receipts | `core`: bounded receipt ordering, decoding and corrupt records; `feedback_history`: durable feedback and branches; `lazy_tools`: incremental tool reads |
| History readers | `cargo test --all-features --locked --lib history::tests`: missing-file policy, 128 MiB bound, metadata changes, cursors and two-reader limit; sync releases its permit before paging fallback |
| Discovery HTTP errors | `discovery history_routes_preserve_distinct_http_errors`: distinct paging, synchronization and tool-detail errors |
| Tool intent | `transcript_details`: live/history parity, late metadata, preserved outcomes and no resurrection of retired or unexecuted calls |
| Runtime and deletion | `runtime_lease_recovery`, `deletion`: process ownership, durable cleanup and shared-history protection |
| Windows background startup | `windows_background`: hidden launcher, duplicate starts, stop/restart, external setup and host identity |

Windows background tests use isolated temporary directories and `gateway-fixture`, which injects an empty legacy-service lookup. They neither touch machine-wide services nor register real login startup. Production service discovery and ownership checks remain enabled, including builds with `test-fixtures`.

Real-OMP smoke test: `cargo test --test omp_smoke -- --ignored`. Set `OMP_EXECUTABLE` if OMP is not on PATH. Fixtures do not establish real provider compatibility.

### Android device checks

Prefer a wireless physical device; use an emulator when needed. Unit tests need no device. Run `sh ./gradlew :app:connectedDebugAndroidTest` for instrumentation tests on the selected attached device.

| Area | Tests / checks |
| --- | --- |
| Activity timeline | `ActivityGroupDeviceTest`, `ActivityTimelineSessionDeviceTest`: mixed tools, focused previews, manual disclosure, reply/settlement/disconnect folding, earlier-turn isolation and enlarged text |
| Work status and cards | `SessionWorkStatusDeviceTest`, `TasksPagerBarDeviceTest`: timeline-only active actions, composer timing/speed, attention and long status labels |
| Markdown | `SessionMarkdownDeviceTest`: tables, links, exact source copying, independent layouts, typography reflow and reading-position/follow-latest behavior |
| Insets | `DirectoryBrowserDeviceTest`: keyboard transitions, create-button clearance, touch delivery and return to the home header |

Timeline scenarios reading older content must swipe backward before `performScrollToNode`; a programmatic jump can race follow-latest layout updates. To resume following after a long reply, swipe forward with a bounded gesture count until `timelineEnd` is visible, then verify that further reply growth is followed. Scope card assertions to the card when host recovery repeats the same label elsewhere.

Device timeline tests use fixture data, not provider calls. `SessionMarkdownDeviceTest` writes `markdown-table.png` to the target app's external files directory; pull it before uninstalling. For inset changes, also check gesture and three-button navigation on an emulator and inspect the signed Release APK on the target phone. `AppScaffold` applies and consumes Material3's system-bar content padding once; do not add route-dependent replacements. The directory browser adds remaining IME padding and its 12dp bottom gap.

For optimized rendering, run only the focused release regression:

```sh
sh ./gradlew :app:connectedReleaseTestAndroidTest -PtestBuildType=releaseTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pinkcollab.ui.ReleaseMarkdownDeviceTest
```

`releaseTest` enables R8/resource shrinking, uses debug signing and a separate application ID, and installs alongside production.

Connection regressions `HostRetryStateTest` and `HostConnectionResumeTest` verify explicit recovery: failed opens and closed sockets stay offline until refresh or Try again. Foreground/network availability does not reconnect. The 20-second heartbeat detects dead sockets; authentication and upgrade failures require their dedicated actions.

### Website checks

Layout changes require desktop and mobile browser review. Check the setup-guide/APK action row both side by side and wrapped, without horizontal overflow. After editing `website/styles.css`, update its SHA-256 prefix in `styles.css?v=...` in both `website/index.html` and `website/404.html`. See [asset maintenance](../scripts/README.md) for artwork and video scripts.

## Discovery regression checks

Run the `discovery` and `catalog` integration suites. They cover adoption, summary-only focus bootstrap despite corrupt detail receipts, and HTTP page provenance across Discovery changes. Android's `SessionCatalogTest` covers cache retention, mixed/missing page versions and stale responses. If the debug Gateway binary is running on Windows, add `--target-dir target-discovery` rather than stopping it.

Fixtures must use temporary absolute `--session-dir` directories so personal OMP history cannot affect results or scan time. Open timestamp fixtures with write access before `set_times` on Windows.

With real OMP installed, run `cargo test --all-features --locked --test discovery real_omp_resumes -- --ignored` for a resume without a provider request.

## OMP RPC v2 and large model catalogs

This transport is between Gateway and OMP; Android still uses Gateway API v4. The stdout reader owns one-way negotiation: `AwaitingReady` → `Negotiating` → `Ready` for RPC v2, or directly to `Ready` for legacy v1. When OMP advertises version 2, Gateway sends `{"id":"protocol-1","type":"negotiate_protocol","protocolVersion":2}` and requires `data.protocolVersion == 2` before ordinary RPCs. Duplicate or late protocol frames cannot renegotiate a ready transport.

| Bound | Behavior |
| --- | --- |
| Startup / negotiation | 30 seconds for `ready`; a separate 10 seconds for negotiation |
| Physical JSON line | Smaller of 1 MiB and the advertised limit |
| Logical response | Smaller of 64 MiB and the advertised limit |
| Chunk sequence | 2–256 uninterrupted, indexed base64 chunks; at most 256 KiB decoded per chunk; 15-second total watchdog |
| Decoded output queue | Capacity one, plus at most one parsed event held by the reader under backpressure |

Gateway validates chunk metadata, ordering, sizes, UTF-8 and the final JSON object. Malformed or incomplete transport fails explicitly. Serialized limits do not bound parsed JSON heap usage. Text deltas use lossless backpressure. Legacy v1 uses unchunked lines and cannot carry responses above the physical bound.

Failures retain exported `omp::TransportError` / `TransportErrorKind` through pending request completion. Tests assert typed categories rather than wording. Readiness sees a terminal error immediately; pending requests and exit notification wait for confirmed process termination. Public output remains `Output::Frame(Value)` or `Output::Exited(Option<String>)`.

Startup must not hold `feedback_order` while awaiting RPC: ordinary events need to drain through the capacity-one queue. After the final RPC, the gate covers durable mapping/state commit only. Validate generation under the controller state lock and keep validation, saving and ready publication together so an exited generation cannot publish stale state.

Run these `model_settings` regressions:

```sh
cargo test --all-features --locked --test model_settings rpc_v2_large_catalog_preserves_model_selection_and_thinking_state
cargo test --all-features --locked --test model_settings startup_event_burst_preserves_state_and_catalog_across_reattach
```

The first transfers more than 600 full models in a response above 1 MiB and checks tail-model selection and thinking levels. The second emits events before `get_state` responses and checks v1/v2 startup, repeated start, stop and reattachment. Keep feedback/history regressions for durable ordering coverage.

### Manual validation with real OMP and OpenRouter

1. Use OMP advertising RPC v2 and configure OpenRouter in OMP. Confirm the actual catalog exceeds 1 MiB in UTF-8; model count alone does not prove chunking.
2. Start Gateway with that executable, pair Android, and start a runtime in an allowed workspace without prompting. Record versions and catalog count/size, without credentials or the full catalog.
3. Open Models and inspect entries near the end. `/api/v4/sessions/:id/models` must retain `{models,thinkingLevels}`, without leaking upstream cost/context/transport fields.
4. Select a tail model and change supported thinking levels, including `off` when available. Confirm the runtime's model identity, level, supported levels and generation remain correct. Reopen the picker and refresh to confirm the selection persists.
5. Stop normally. Send a provider prompt only when that use and cost are authorized. Report actual observations separately; fixture success is not a real OpenRouter validation.

## Local profiling

HTTP `Server-Timing: gateway;dur=...` measures handler time, excluding transfer and compression. From `gateway/`, `PINKCOLLAB_STARTUP_TIMING=1 cargo run -- serve` logs snapshot timings without credentials or transcripts. In PowerShell, set `$env:PINKCOLLAB_STARTUP_TIMING = '1'` before running `cargo run -- serve`.

Run `cargo test --locked --test catalog_cost -- --ignored --nocapture` for a synthetic 10,000-session comparison of the former full-projection algorithm and current cold/warm catalog ledger. It checks complete count/selection and prints timing and selected-summary bytes. This measures local handler paths, not phone/network latency or heap use. Cold ledger rebuilds still read all managed metadata; only Runtime Summary selection is bounded.
