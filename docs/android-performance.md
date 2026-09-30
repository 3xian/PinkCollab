# Android performance review

Review date: 2026-09-30. Scope: the current Android module and the supplied optimization proposal. The proposal is a checklist, not evidence that every listed problem exists.

## Assessment of the proposal

| Suggestion | Finding and decision |
| --- | --- |
| Split UI state | Valuable. The root composable collected transcripts and all draft/operation maps. Navigation now observes explicit `NavigationState`/`NavigationHost` models without transcripts, credentials, or socket revision counters. Task lists and task details have separate presentation flows; task-specific flows are collected inside the task route. Repository state stays atomic. |
| Batch token/chunk updates | Valuable at the presentation boundary. Protocol v3 sends complete timeline upserts rather than token append events. Reduce every event in order, then conflate only cumulative detail snapshots in a 75 ms window. Session lists and navigation remain current while Tasks is off screen. Never debounce the event stream or discard protocol frames. |
| Lifecycle-aware collection | Already implemented throughout the app. New collectors also use `collectAsStateWithLifecycle`; the batched flow stops when its subscribers stop. |
| Lazy list keys and `derivedStateOf` | Timeline/history lists already used message keys. Streaming and attention rows now also have keys; timeline rows have content types. Session sorting and key derivation are remembered. `derivedStateOf` is not a blanket substitute for `remember`. |
| Markdown cache and incremental rendering | Main-thread parsing was a real issue. Parsing now runs on `Dispatchers.Default`, serialized per renderer, with a screen-owned LRU keyed by content. Cache entries distinguish edited content. The cache allows 64 entries and 256,000 source-plus-rendered characters; oversized messages render without being retained. A new message appears as plain text while its first render is prepared. Updates to that message retain the preceding formatted result until the latest replacement finishes; cancelled parses cannot overwrite it. A different message ID starts with its own render state. An append-only renderer would be incorrect for replacement upserts and edits to earlier Markdown syntax. |
| Coroutine dispatchers | HTTP bodies and WebSocket JSON already run on OkHttp callbacks; history parsing, credential reads, selected-file reads, and outbox storage already use background dispatchers. Model catalog and directory JSON conversion now also run on `Default`. Moving every `viewModelScope.launch` to `IO` would mix UI coordination with blocking work unnecessarily. |
| Release shrinking | Valuable and implemented: R8, resource shrinking, the optimized default ProGuard configuration, and optimized resource shrinking for AGP 8.13.2. Keep the ViewModel constructor used by the lifecycle factory and stack-trace source locations. Dependency consumer rules remain active. |
| Remove extended icons / ZXing | Keep current functionality. Numerous icons are outside the core set, and QR scanning is part of host pairing. R8 removes unused code without a broad icon rewrite or a new delivery mechanism. |
| Startup / Baseline Profiles | `MainActivity` already calls `setContent` without blocking network/file reads first. Startup animations now read state during placement/drawing rather than recomposing their enclosing UI each frame. The existing three-second minimum startup animation is a product behavior, not a measured CPU bottleneck. Do not invent a `baseline-prof.txt`; a useful profile needs generated, representative startup/navigation/session workloads. |
| `SavedStateHandle` draft storage | The warning is valid in principle: drafts are unbounded and can eventually exceed the saved-state transaction budget. Session history is not saved there. Simply switching to IDs would lose drafts without a durable store. A separate persistence migration must preserve draft intent IDs, attachment permissions, restoration order, and user edits; this review does not change that contract. |

## Implementation

- `ui/PresentationFlows.kt`, `CollabViewModel.kt`, and `PinkCollabApp.kt`: explicit navigation state, continuously updated session lists, and bounded detail refresh frequency. The detail flow stops and clears its replay cache when Tasks has no subscribers. Input, upload progress, and command state use their own flows and are not throttled.
- `ui/SessionMarkdownRenderer.kt`, `TextRenderCache.kt`, `SessionTimeline.kt`, and `SessionPage.kt`: background Markdown rendering, bounded reuse across lazy-row recreation, cancellation of obsolete render jobs, stable keys, and content types.
- `ui/TasksScreen.kt`: reuse sorted sessions and derived session keys when the session lists have not changed. Keep a requested navigation selection pending until the list contains it and the pager has applied it; an old page cannot overwrite a newly created session selection. Once fulfilled, regular swipes and selected-session removal update selection normally.
- `ui/StartupLoadingScreen.kt`: defer animation state reads into layout, graphics-layer, and Canvas callbacks.
- `data/ProtocolReducer.kt`: index IDs once for multi-item upsert batches instead of rescanning the transcript for each update. Single-message updates avoid allocating an index. Replacement/removal/reset ordering and last-upsert-wins behavior are preserved.
- `data/SessionGateway.kt` and `DirectoryGateway.kt`: parse potentially large responses off the main thread.
- `app/build.gradle.kts`, `app/proguard-rules.pro`, and `gradle.properties`: enable release optimization. This is a local build change; no version bump or publication was performed.

## Validation and measured results

The original checkout built successfully and passed its existing unit tests before changes. Its signed, unminified release APK was **22,399,060 bytes**. The optimized signed APK is **11,876,267 bytes**, a **46.98% reduction** (decimal MB: 22.40 to 11.88).

- Final `testDebugUnitTest`: **178 passed**, zero failures/errors/skips.
- `lintDebug`: **zero errors**, 18 warnings (dependency/API and existing style warnings remain).
- `assembleRelease`: successful, including R8 and release lint. APK: `android/app/build/outputs/apk/release/app-release.apk`. Retracing output is under `android/app/build/outputs/mapping/release/`.
- Android 15 emulator device regression after the review fixes: **30 passed, one skipped** (31 XML test cases). The existing real-device keyboard test is intentionally skipped on emulators. These are fixture-based UI/storage tests, not a live Gateway load test.
- Optimized release runtime smoke: installed the signed APK; checked cold launch, loading animation, empty-session screen, pairing sheet, and ZXing camera scanner activity. The crash buffer remained empty. This verifies scanner launch, not decoding a real pairing QR or establishing a live WebSocket session.
- The emulator process exited during the first release smoke attempt. Restarting it with software GPU rendering completed the smoke checks. Screenshots, UI trees, and the final empty crash log are retained locally under `android/app/build/reports/performance-review/`.
- The review-fix run initially hit a Debug/Release install-signature mismatch on the test emulator. After the test framework cleaned the previous installation, the full device suite passed. The rebuilt optimized Release also cold-launched into the home screen with an empty crash log; final evidence uses the `fix-release-*` filenames in the same report folder.

Commands from `android/` on Windows:

```powershell
cmd.exe /c gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:lintDebug
cmd.exe /c gradlew.bat :app:connectedDebugAndroidTest
```

Regression coverage includes burst coalescing, final-value delivery, cancellation, typed navigation projection, off-screen session-list updates without subscribers, cache reuse/concurrent requests/eviction/oversized messages, background parsing, and ordered timeline batches. Android device tests check formatted Markdown, code blocks, links, text selection, and edited-message rendering. A blocked parse verifies that replacements retain the preceding formatted result and a cancelled replacement cannot overwrite the latest one. Pager regressions verify pending-created-session selection, normal swipes after that selection, and removal of a fulfilled selection. Existing device tests exercise history, drafts, model selection, controls, resource screens, and encrypted outbox storage.

The deterministic burst test delivers an initial snapshot and only the latest of 100 subsequent updates at the next 75 ms boundary. This establishes bounded presentation frequency under load; it does not measure real-device frame time, main-thread CPU usage, or long-running memory growth. Likewise, moving parsing off the main thread is a structural improvement, not a claimed startup speedup. Live Gateway stress runs, real-device keyboard behavior, and generated Baseline Profiles remain follow-up measurements.

## References

- [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices): cache expensive calculations and defer state reads to the phase that needs them.
- [Enable app optimization with R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization): code/resource optimization and AGP-specific resource shrinking configuration.
