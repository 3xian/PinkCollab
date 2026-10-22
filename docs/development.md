# Development

Build the Android app and Gateway from the same checkout. Requirements: Rust 1.89+, JDK 17, Android SDK 36, and Node.js 18+ for npm and website checks.

## Run the Gateway during development

OMP must be on `PATH`. From `gateway/`:

```sh
cargo run -- init --workspace /absolute/path/to/projects  # once
cargo run -- serve
```

`cargo run -- serve` serves in the foreground. Put `--` before Gateway arguments (`cargo run -- pair`). The data directory defaults to `~/.pinkcollab`; pass `--data-dir <path>` after `--` on every command that should use another one. HTTPS and pairing: [Deployment](deployment.md#tailscale-funnel).

## Build the Gateway

From `gateway/`:

```sh
cargo build --release --locked --bin pinkcollab-gateway
```

Binary: `target/release/pinkcollab-gateway` (`.exe` on Windows).

## Build Android

From `android/`:

```sh
sh ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`; set `sdk.dir` in `android/local.properties` or `ANDROID_HOME`. Debug and release APKs use different signing keys, so switching requires uninstalling the app and loses phone-side pairings. Release signing: [Releases](npm-release.md).

## Validation

From `gateway/`:

```sh
cargo fmt --check
cargo clippy --all-targets --all-features --locked -- -D warnings
cargo test --all-features --locked
```

Focused Gateway history/receipt regressions:

```sh
cargo test --all-features --locked --lib history::tests
cargo test --all-features --locked --test core
cargo test --all-features --locked --test discovery history_routes_preserve_distinct_http_errors
```

Receipt reads share field decoding and use one query for the bounded recent-operation window; `core` covers ordering, session filtering, the 100-record cap, optional/JSON/timestamp fields, and corrupt selected records. History unit tests cover missing-file policy, the 128 MiB limit, metadata changes, cursors, and the two-reader limit. Sync must release its reader permit before falling back to ordinary paging. The HTTP regression preserves the distinct paging, synchronization, and tool-detail error responses; `feedback_history` and `lazy_tools` cover feedback merging, branches, and incremental tool updates.

Real-OMP smoke test (needs OMP or `OMP_EXECUTABLE`): `cargo test --test omp_smoke -- --ignored`.

On Windows, `cargo test --all-features --locked --test windows_background` checks the hidden login launcher, duplicate starts, graceful stop/restart, external setup, and preserved host identity using isolated temporary directories. It runs the dedicated `gateway-fixture` binary, which uses the real CLI and desktop lifecycle but injects an empty legacy SCM lookup at the service factory. It neither queries nor modifies machine-wide Windows services and does not register real login startup. The production `pinkcollab-gateway` binary retains legacy service discovery and ownership checks, including when built with `test-fixtures`.

From `android/`: `sh ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` (`gradlew.bat` on Windows).
Android unit tests run offline; `:app:connectedDebugAndroidTest` needs an attached test device or emulator. `ActivityGroupDeviceTest` and `ActivityTimelineSessionDeviceTest` cover continuous mixed-tool grouping, latest-preview handoff, manual disclosure, summary-only previews without full-detail reads, reply-triggered folding of every disclosure (including off-screen manually opened groups), settlement/disconnect folding, earlier-turn isolation, long sequences and enlarged text on the actual session surface. Their full-page screenshots use fixture timeline data rather than provider calls; Edit coverage parses a supplied summary and opens its filename and patch preview without a detail request. `SessionWorkStatusDeviceTest` checks that active actions appear only in the timeline while duration and speed remain above the composer, including the pre-tool Thinking phase, and that attention remains actionable. `TasksPagerBarDeviceTest` checks that a long status stays in the card's top-right corner and does not cover the host or project line at large font scales. Its layout queries are scoped to the session card because host recovery can display the same connection status elsewhere on screen.

Android does not reconnect a host when the app returns to the foreground or the default network becomes available. A failed open, socket close, or lost network leaves that host offline until the user refreshes or taps Try again; each of those opens one socket and does not retry on its own. Authentication-required and upgrade-required hosts stay blocked until the user acts. The 20-second WebSocket heartbeat still detects a dead socket, which then stays offline. Android Doze, process termination, and vendor battery restrictions can still interrupt connections. `HostRetryStateTest` checks that a rejected open does not start another attempt. `HostConnectionResumeTest` checks that a closed socket stays offline until an explicit connect.

`AppScaffold` uses Material3 Scaffold's default system-bar insets and applies and consumes its content padding once. Do not disable those insets and add route-dependent status-bar or navigation-bar padding afterward: the shell must handle both the presence and removal of its top app bar. The directory browser adds only the remaining IME padding and a 12dp bottom gap. Run `DirectoryBrowserDeviceTest` on an edge-to-edge emulator in both gesture and three-button navigation modes. It uses the same transparent dark system-bar styles as `MainActivity`, checks the create-session button gap and lower-edge touch delivery through keyboard transitions, and verifies the home header remains below the status bar after visiting Workspaces and the directory browser and returning. Also inspect that round trip and the browser button in the installed signed Release APK on the target phone; debug fixture results alone do not verify the production activity's inset behavior.

Run the optimized Android rendering regression on an attached device or emulator:

```sh
sh ./gradlew :app:connectedReleaseTestAndroidTest -PtestBuildType=releaseTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pinkcollab.ui.ReleaseMarkdownDeviceTest
```

`releaseTest` enables release R8/resource shrinking, uses debug signing, and installs alongside the production app. Use the focused test above rather than the debug UI suite.

`SessionMarkdownDeviceTest` covers table-cell alignment and inline links, full-width layout without unnecessary wrapping, exact source copying, row additions during message updates, and replacement of a table with plain prose. It also checks independent wrapping for identical tables in different-width messages, removal of one duplicate, same-width typography reflow, and source visibility when Markdown renders no text. Its table scenario writes `markdown-table.png` to the target app's external files directory for visual inspection; pull it before uninstalling the test app.

From the repository root:

```sh
node npm/scripts/check-release.mjs
npm test --prefix npm/pinkcollab
npm pack ./npm/pinkcollab --dry-run
node .github/actions/check-website/check-site.mjs
```

API changes must match the [protocol](protocol.md).

Website layout changes also need a browser check at desktop and mobile widths. The setup-guide button and release APK link use a wrapping action row with a 16px horizontal gap and a 12px gap between rows; verify both side-by-side and wrapped layouts without horizontal overflow. After changing `website/styles.css`, update its SHA-256 prefix in the `styles.css?v=...` references in `website/index.html` and `website/404.html` so cached pages load the new styling.

## Discovery regression checks

Run `cargo test --all-features --locked --test discovery` for the discovery/adoption regressions. `cargo test --all-features --locked --test catalog` checks summary-only focus bootstrap with corrupt detail receipts and HTTP page provenance when Discovery changes and returns to an earlier catalog version. Android's `SessionCatalogTest` covers matching-version cache retention, mixed or missing page versions, unrelated activity during reads, and stale-response rejection. If the debug Gateway executable is running on Windows, add `--target-dir target-discovery` instead of stopping the Gateway.

With OMP installed, run `cargo test --all-features --locked --test discovery real_omp_resumes -- --ignored` for a real resume without a provider request. Set `OMP_EXECUTABLE` when it is not on PATH.

## OMP RPC v2 and large model catalogs

This is a Gateway-to-OMP transport change, not an Android or Gateway HTTP/WebSocket API change. The stdout reader alone owns the one-way protocol state: `AwaitingReady` → `Negotiating` → `Ready` for v2, or `AwaitingReady` → `Ready` for legacy v1. Startup waits up to 30 seconds for OMP's `ready` and, when `supportedProtocolVersions` includes `2`, sends `{"id":"protocol-1","type":"negotiate_protocol","protocolVersion":2}`. Runtime readiness is published only after a successful response with `data.protocolVersion == 2`. One watch-based supervisor enforces the startup and negotiation deadlines; negotiation has its own 10-second deadline after `ready`. Late or duplicate protocol frames cannot renegotiate a ready transport, and an ordinary business response with id `protocol-1` after readiness is dispatched normally without changing protocol state.

Readiness observes a terminal transport error immediately, without waiting for the process termination grace period. Pending requests and the exit notification are released only after process termination is confirmed; the same typed cause is retained for those requests.

The physical JSON-line bound is the smaller of the local **1 MiB** limit and OMP's advertised limit; the logical reassembly bound is the smaller of **64 MiB** and OMP's advertised limit. These are strict byte bounds, not character counts. RPC v2 can carry large logical responses as uninterrupted, indexed base64 chunks, with 2–256 chunks and at most 256 KiB of decoded bytes per chunk. Gateway validates chunk metadata, ordering, decoded sizes, UTF-8, and the final JSON object before dispatching one logical frame. Malformed, oversized, interrupted, or incomplete frames terminate the transport with a reason rather than leaving a request waiting; an incomplete chunk sequence has a total 15-second watchdog. The decoded output queue has capacity one, bounding the number of queued large JSON values and applying backpressure to the reader; the logical frame limit still applies to each value.

The reader holds at most one additional parsed event while waiting for that queue; it does not collect a second chunk sequence under backpressure. JSON objects have allocation overhead, so 64 MiB is a serialized-message bound, not a promise that the parsed heap uses only 64 MiB. Additive text deltas use the same lossless backpressure path rather than being discarded when the queue is full.

Transport failures remain typed through pending request completion: callers can downcast an `anyhow::Error` to exported `omp::TransportError` and inspect its public `kind: omp::TransportErrorKind`. Categories are `PhysicalFrameTooLarge`, `ChunkPayloadTooLarge`, `ReassemblyTooLarge`, `InvalidChunk`, `InvalidUtf8`, `InvalidJson`, `InvalidFrame`, `IncompleteChunk`, `Negotiation`, `Timeout`, and `Io`. Chunk metadata, ordering, base64, and length mismatches use `InvalidChunk`; an oversized logical declaration uses `ReassemblyTooLarge`; parsed nonobjects or nested chunks use `InvalidFrame`; EOF during a chunk sequence uses `IncompleteChunk`. Tests assert categories rather than diagnostic wording. The public output stream remains `Output::Frame(Value)` or `Output::Exited(Option<String>)`.

Legacy RPC v1 remains supported and uses unchunked JSON lines. It cannot carry a model response above the physical bound; OMP's existing oversized-response failure is preserved. Oversized physical lines, malformed JSON, and EOF-truncated JSON are explicit transport errors, not an empty catalog or a reason to raise the physical limit. Use an OMP build advertising RPC v2 for a large OpenRouter catalog.

The fixture regression is `cargo test --all-features --locked --test model_settings rpc_v2_large_catalog_preserves_model_selection_and_thinking_state`. It opts into `--rpc-v2 --large-models`, retains the fixture's first two models, and transfers more than 600 full upstream models in a logical response larger than 1 MiB. It checks the existing `/api/v4/sessions/:id/models` consumer projection, tail-model selection, thinking-level changes, and refreshed runtime state. This fixture does not replace real OMP validation.

Startup must not hold the controller's `feedback_order` gate while awaiting any OMP RPC. Ordinary events can precede a response and must drain through the capacity-one output queue while the projection is `starting`; the event consumer still validates their runtime generation, and state-neutral command-catalog updates do not change the model or generation. After the last startup RPC, the gate covers only the durable session mapping/state commit, preserving ordering with feedback delivery and history capture. The controller state lock validates the active generation before mapping mutation and keeps that validation, the saved session, and ready-state publication together; an exited or replaced generation cannot publish stale startup state.

Run `cargo test --all-features --locked --test model_settings startup_event_burst_preserves_state_and_catalog_across_reattach` for the actual Gateway API/SessionController regression. The fixture's `--startup-event-burst` emits three ordinary `available_commands_update` events before every `get_state` response. The test exercises v1 and v2 startup, stable generation/model/catalog on an already-running start, normal stop, and a fresh generation reattaching the saved session while restoring the current default model. The previous lock scope prevents the response from reaching its pending request and fails this regression. Existing feedback/history regressions remain necessary for durable ordering coverage.

### Manual validation with real OMP and OpenRouter

These are verification steps to perform, not a record of a completed run:

1. Install a real OMP build that advertises RPC v2. Configure OpenRouter credentials and a model scope exposing more than 600 models in OMP itself. Confirm the actual `get_available_models` response includes full model metadata and exceeds 1 MiB in UTF-8; model count alone does not establish that chunking was exercised. Do not include credentials or the full catalog in a published report.
2. Start Gateway against that executable (configure the `omp` setting if it is not the `omp` on `PATH`), pair the existing Android app, create a session in an allowed workspace, and start its runtime without prompting. Confirm startup completes without a timeout or transport error; record OMP/Gateway versions and catalog count/byte size.
3. Open Android's model picker. Confirm the large catalog loads, including models near its end, with provider, model name, and supported thinking choices intact. For an authenticated API check, `GET /api/v4/sessions/:id/models` must retain the existing `{models,thinkingLevels}` shape; full upstream cost/context/transport metadata must not become new consumer fields.
4. Select an available model near the end of the catalog. Confirm the runtime snapshot's `model.provider`, `model.id`, and `model.name` reflect that selection. Change a supported thinking level, then change it again (including `off` if available), and confirm `model.thinkingLevel` and `model.thinkingLevels` refresh without losing the selected model or generation.
5. Reopen the picker and refresh the session. Confirm the complete catalog and selected model persist for that runtime. If desired, send a small prompt only with approval for the provider request/cost and confirm ordinary streaming still works after negotiation. Stop the runtime normally.

Report the observed results separately from these instructions, including any diagnostic on failure. Never label fixture-only coverage as a successful OpenRouter integration run.

## Local profiling

HTTP responses include `Server-Timing: gateway;dur=...` for handler time, excluding body transfer and compression. Run `PINKCOLLAB_STARTUP_TIMING=1 cargo run -- serve` from `gateway/` to log host/session snapshot timings without credentials or transcript text.

For a reproducible synthetic directory comparison, run `cargo test --locked --test catalog_cost -- --ignored --nocapture` from `gateway/`. It creates 10,000 detached managed sessions in temporary storage and compares the former full-summary/JSON/fingerprint projection with cold- and warm-ledger bounded bootstrap. Both paths use the current DTO; the test prints elapsed time, final selected-summary bytes, and checks complete count/selection. The baseline reproduces the old projection algorithm rather than running an older binary. Treat this as local handler-path evidence, not a release benchmark, heap measurement or network/phone latency result. Cold metadata cache rebuilds still read all managed rows; only the Runtime Summary selection is bounded.
