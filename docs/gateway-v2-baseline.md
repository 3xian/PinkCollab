# Gateway v2 implementation baseline

This document records the evidence used for the v2 migration. Gateway API v2 is independent of OMP's RPC transport version.

## Repository baseline

- Starting commit: `0033273e2f0fb8d032134a5d70629f154a32a3ae`.
- Existing contract: `docs/protocol.md` describes Gateway API v1. Creating a session starts an OMP process, and Android merges REST and WebSocket data using timestamps.
- `cargo test --all-targets` passed on this commit: 22 tests passed, one real OMP smoke test ignored. The fixture feature tests were not included in that command.
- The ignored `real_omp_rpc_ui_handshake` test was run separately against the installed OMP and passed.

## Tested OMP capability boundary

The installed package is `@oh-my-pi/pi-coding-agent` **18.3.1** (`omp --version` prints `omp/18.3.1`). The authoritative definitions for this matrix are the installed package's `src/modes/rpc/rpc-types.ts` and the actual smoke test, not the moving upstream `main` branch.

| Capability | Evidence in 18.3.1 | Gateway v2 decision |
| --- | --- | --- |
| `--mode rpc-ui`, ready, `get_state`, abort, clean stop | Real smoke test passed | Enabled |
| RPC transport v1 | Ready definition advertises v1 and v2; current adapter reads bounded v1 frames | Enabled with 1 MiB frame limit |
| RPC transport v2 chunks | Type definition supports negotiation and chunks; Gateway has no chunk decoder | Disabled until adapter and limits are tested |
| Prompt acceptance and completion | `prompt` response, correlated `prompt_result`, and `session_settled` are defined; fake OMP exercises the flow | Enabled against the tested type contract; a provider-backed real prompt remains unverified |
| `agent_end` as quiescence | Definition explicitly distinguishes yield from settled | Never used as quiescence evidence |
| Explicit steer and follow up | `steer`, `follow_up`, and `streamingBehavior` are defined | Enabled after adapter test |
| Model and thinking level | `get_available_models`, `set_model`, `get_available_thinking_levels`, `set_thinking_level`, `get_state` are defined | Independent actions; no Ctrl+P role semantics |
| Session loading | `switch_session` is defined and a real smoke test reloads the first runtime's session file into a second runtime | Enabled only for the server-side stored session reference |
| History pagination | `get_messages_page` is defined | Do not infer that it works offline; use branch-bound file reading for detached sessions |
| Graceful EOF | Upstream RPC documentation says stdin close drains accepted commands and stdout | Keep stdout draining while waiting for actual process exit |

The upstream [RPC reference](https://github.com/can1357/oh-my-pi/blob/main/docs/rpc.md) is useful for discovery but changes independently of the tested package.

## Migration constraints

The v1 SQLite `sessions.metadata` JSON contains both management and runtime fields. V2 migration must extract only id, host id, canonical workspace, title, creation and activity timestamps, and the private OMP session file mapping. Identity, clients, and unused pairing tokens stay intact. OMP transcript files are not modified.

## Implementation verification

- `cargo test --all-targets --features test-fixtures`: 31 tests passed; the separate real OMP test is ignored by the default suite. This includes concurrent Stop callers and a Windows child-process cleanup test.
- `cargo test --test omp_smoke real_omp_rpc_ui_handshake -- --ignored`: passed with installed `omp/18.3.1`. It checks ready, state, model/thinking queries, session reload into a second process, and clean stop without invoking a provider. It does not verify a paid model prompt or every OMP error form.
- `cargo clippy --all-targets --features test-fixtures -- -D warnings`: passed.
- `cargo build --release`: passed.
- Android `:app:testDebugUnitTest :app:assembleDebug`: passed. Physical-device reconnect has not been exercised here. Windows process-tree cleanup was exercised with a fixture child; the Job Object is attached immediately after spawn, so a process created in that brief interval is outside the fixture's coverage.

Boundaries in this implementation: OMP transport frames are capped at 1 MiB; a live session keeps at most 64 display items and 8 KiB of text per item; history reads allow two concurrent readers, up to 128 MiB per source file, 16 MiB per line, 100 items per page, and 8 MiB per response. The WebSocket broadcast ring has 32 entries and sends `resync_required` when a subscriber falls behind. These are configured limits, not measured performance gains. No comparable pre-v2 load benchmark was captured, so no CPU, memory, or latency improvement is claimed.
