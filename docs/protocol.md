# PinkCollab protocol 3

## Overview and pairing

The sole client API is `/api/v3`. Pairing and host snapshots declare `protocolVersion:3`; older API routes return HTTP 426 (`protocol_upgrade_required`). Upgrade Android and Gateway together. OMP's local RPC version and the database schema version are independent.

`POST /pair` accepts `{token,name}` and returns `{clientId,credential,host,protocolVersion}`. A pairing token expires after five minutes and is single use. All other routes, including WebSocket upgrade, require `Authorization: Bearer <credential>`. Credentials never appear in URLs. Browser `Origin` headers are rejected; host-side revocation terminates access.

Paths below are relative to `/api/v3`. JSON fields use camelCase.

## Core models

- **Session**: `{id,hostId,cwd,title,createdAt,updatedAt,origin}`. `origin` is `managed` or `discovered`; clients must use this field rather than infer origin from ID/title/runtime. Missing origin from older Gateway builds is interpreted as managed. Managed records are persistent conversation metadata, independent of the process. Titles default to `New session`; an accepted prompt replaces the title with its normalized first 80 characters. Attachment-only prompts retain the title.
- **Runtime**: `null`, or `{generation,state,activity,model,pendingInputs,workTiming}`. `state` is `starting`, `idle`, `running`, `waiting_input`, or `stopping`. Completing a prompt can leave an idle runtime attached. `model` contains `{provider,id,name,thinkingLevel,thinkingLevels,fastModeEnabled?,fastModeActive?}`; unavailable values can be null. Fast fields are omitted when OMP does not report them. `fastModeEnabled` is the preference for the active model family; `fastModeActive` indicates whether it is currently effective. Input requests contain `{id,type,text,options}`.
- **Operation**: `{id,kind,state,error}`. `id` is the submitted commandId; `kind` is its command type. `state` is `pending`, `succeeded`, `failed`, `cancelled`, or `unknown`. `error` is null or `{code,message}`. Persistence fingerprints, dispatch stages and result objects are private.
- **Timeline item**: `{id,kind,text,detail,timestamp,tool?}`. Tool traces contain `{callId,name,arguments,result,isError,completed}`. Live IDs are generation-scoped; live previews are bounded to 64 items with bounded text. Full text belongs to history.

`workTiming` is null if the start of work is unknown. Otherwise `{elapsedMs,running,completed}` samples Gateway monotonic work time: pending inputs pause it, settlement freezes it, and a new round resets it. `completed` means settled, not successful. Clients anchor samples to their own monotonic clock and extrapolate only while online. It is display timing, not precision profiling.

## WebSocket

Connect to `WS /events`. The first frame is:

```json
{"type":"host_snapshot","protocolVersion":3,"host":{},"sessions":[{"session":{},"runtime":null}],"workspaces":[]}
```

The host list receives creation and authoritative state updates without subscribing to details. For the visible session, send `{"type":"subscribe","sessionId":"..."}`; to stop receiving its timeline and operations, send `{"type":"unsubscribe","sessionId":"..."}`. Each subscribe replaces its view with:

```json
{"type":"session_snapshot","sessionId":"...","session":{},"runtime":null,"timeline":[],"operations":[],"hasHistory":false}
```

A subscribe may include `historyLimit` (1–100). Gateway then attempts to include an optional `history` first page using the same `{items,source,nextCursor}` shape and cursors as the history REST endpoint. History is read separately from the snapshot; it is not part of its event fence. If the read fails, takes over one second, or the serialized page exceeds 256 KiB, Gateway omits `history` and the client loads it through REST. Android requests the latest 10 items to minimize first-content transfer and loads earlier pages on demand. Clients that omit `historyLimit`, and older Gateways, retain the REST flow.

`operations` contains up to 20 recent receipts. Use receipt lookup for older pending commands. `hasHistory` indicates a server-side history mapping, not a guarantee that the transcript is already available.

| Event | Fields besides `type` | Apply |
| --- | --- | --- |
| `session_upsert` | `sessionId`, `session`, `runtime` | Insert/replace a host-list entry |
| `session_state` | `sessionId`, `session`, `runtime`, `hasHistory` | Replace metadata and runtime in host list and any open detail |
| `timeline` | `sessionId`, `upsert`, `remove`, optional `reset` | If reset, clear live items; remove IDs; then insert/replace items by ID |
| `operation` | `sessionId`, `operation` | Replace receipt by its ID |

A null runtime clears all runtime facts. There is no separate exit event. Runtime start/exit resets the live tail; history is unaffected. Metadata updates also contain the complete runtime. Unknown frame types are protocol errors.

Clients may offer the optional `pinkcollab.v3.gzip` WebSocket subprotocol. When accepted, Gateway sends JSON frames of at least 1 KiB as gzip-compressed binary messages; smaller frames remain text. Clients decode a binary message only after negotiation and bound decoded messages to 8 MiB. Without negotiation, all frames remain text. Client commands remain text in both cases.

The ordered socket is the event stream. Clients maintain no wire subscription IDs or revision cursors. Gateway registers its receiver before capturing a snapshot and uses private sequence fences to discard superseded queued events. Snapshot validation tracks only mutations represented by that snapshot: timeline/operation changes do not invalidate the host list, and one session cannot invalidate another session’s snapshot. If it cannot capture a stable snapshot, loses broadcast events, encounters an oversized event, or cannot deliver promptly, it closes the connection. Reconnect takes a fresh host snapshot and resubscribes to the visible session; there is no durable event replay. In-flight history responses from a previous local view must be discarded. Host and session snapshots are separate reads, not a global transaction.

## Commands and generation safety

`POST /sessions` accepts `{commandId,hostId,cwd,title?}` and returns a Session. It checks the workspace and creates no process. Creation is idempotent within `(clientId,commandId)`.

`POST /sessions/:id/commands` returns `{operation,receiptStored}`: HTTP 202 for a new command, 200 for an identical replay.

```json
{"commandId":"intent-1","type":"prompt","generation":"run-1","message":"Check the build","fileIds":[]}
```

| Type | Fields besides `commandId`, `type` |
| --- | --- |
| `start_runtime` | none; attach without a prompt |
| `prompt` | `message`, optional `fileIds`, optional `generation` |
| `interrupt`, `stop_runtime` | `generation` |
| `respond` | `generation`, `inputRequestId`, optional `value`, `confirmed`, `cancelled` |
| `select_model` | `generation`, `provider`, `modelId` |
| `set_thinking_level` | `generation`, `level` |
| `set_fast_mode` | `generation`, `enabled` (boolean) |

Generation guards target one process lifetime. A mismatch fails without affecting a newer runtime. A prompt with no generation means the caller observed **no runtime**: Gateway starts one only if still absent. An attached runtime requires its generation. Gateway routes idle prompts normally and running prompts as steering; Android sends no delivery choice. Starting/stopping runtimes and pending input reject inappropriate work. Stop confirms process-tree exit before releasing its slot; Interrupt keeps the process.

## Idempotency and operation recovery

Command identity is `(clientId,sessionId,commandId)`. Repeating the same semantic request returns its receipt without dispatching again; changing its body returns `idempotency_conflict`. `GET /sessions/:id/operations/:commandId` reads the authenticated client's durable receipt even when the original HTTP response was lost.

Gateway persists a receipt before dispatch and records the external-send boundary. Public `pending` covers its internal accepted/dispatching/running stages. On restart, accepted work is cancelled; work that may have reached OMP becomes `unknown`. Neither is automatically replayed. Unknown means the effect cannot be proven; it must not be presented as successful or safe to retry.

Android persists encrypted commands before POST, recovers by receipt lookup, and retains a stable ID for each prompt intent. Stop is lookup-only after its first send attempt. A storage failure may allow emergency Stop with `receiptStored:false`; inspect runtime state, never automatically resend it. Only HTTP 404 with `operation_not_found` proves a receipt is absent. During the protocol upgrade, Android discards never-sent old payloads and makes all previously sent outbox entries lookup-only.

## History and files

`GET /sessions/:id/history?limit=50&cursor=...` returns `{items,source,nextCursor}` (limit 1–100). Cursors are opaque and bound to a transcript source/branch; `stale_cursor` restarts pagination. `nextCursor:null` ends pagination. History stays separate from the WebSocket live preview; clients must not guess cross-source deduplication from text.

Before a prompt can reach OMP, Gateway durably marks its mapped transcript as possibly written. A missing or corrupt transcript then fails explicitly (`history_unavailable`); resume cannot silently replace it, even after an aborted/failed/unknown prompt. Only an unwritten mapping with no file can return empty history (`source:null`) and be safely replaced. Resume always gets a new generation.

`PUT /sessions/:id/files/:fileId?name=...` uploads raw `application/octet-stream` bytes. IDs are `file_` plus 32 hex digits; each file is 1 byte–10 MiB, with up to five files per prompt. Identical retries are idempotent. References are scoped to the session. Idle prompts use quoted host file mentions; steering uses host paths and embeds recognized images up to 512 KiB. Frames exceeding the 1 MiB OMP limit fail. Uploaded files remain in the Gateway data directory until removed there.

Other core reads: `GET /fs/list?path=...` returns `{path,parent,directories}` within allowed roots; `GET /sessions/:id/models` returns `{models,thinkingLevels}` from the attached runtime.

Detached conversations display `OMP default`. When restoring their transcript, Gateway reads the new OMP process's default model before loading history, then selects that model before sending a prompt. The attached runtime reports the actual model name; later explicit model changes apply for that runtime.

## Errors and optional REST reads

Errors use `{code,message}`. Program logic uses `code`; `message` is display text, never a parser contract. Typical codes: `authentication_required`, `workspace_forbidden`, `generation_mismatch`, `runtime_required`, `runtime_not_ready`, `input_pending`, `input_expired`, `invalid_request`, `invalid_file`, `idempotency_conflict`, `persistence_unavailable`, `stale_cursor`, `history_unavailable`. Durable command failures appear in the Operation, even if acceptance returned 202. Internal error chains and raw OMP error objects are not public DTOs.

Optional inspection APIs (Android uses snapshots instead): `GET /host`, `GET /workspaces`, `GET /sessions?limit=50&cursor=...` (creation-order paging), and `GET /sessions/:id` (session snapshot fields without event type/sessionId). These use the same public DTOs.

## Discovered OMP history

Existing host OMP sessions share `/sessions` pagination, host snapshots, session snapshots, history and commands. `origin: discovered` always has `runtime: null`; its session snapshot has `hasHistory: true`, empty `timeline` and empty `operations`. Timestamps describe stored metadata only. No transcript path or OMP-specific representation is exposed. Display it as History, never Ready/running/completed.

A generation-less `prompt` or `start_runtime` lazily adopts it and publishes the normal flattened `session_upsert` followed by normal runtime/session events. Its ID does not change; `origin` becomes `managed`, replacing the existing card. Uploading an attachment or reading a snapshot/history does not adopt. Command IDs, outbox scope, receipt lookup and cursor binding are unchanged.

Admission can return HTTP 409 `external_session_busy` (close external OMP and retry), HTTP 503 `history_unavailable`, or HTTP 404 `session_not_found`. No receipt exists if admission fails before command persistence. Startup after adoption can fail its durable operation with `external_session_busy` or `history_unavailable`; the managed mapping is retained.

See [refresh and discovery limits](deployment.md#refresh-and-discovery-limits) and [external-writer limitations](deployment.md#external-writers) for the discovery and admission boundaries.

## Provider usage

`GET /usage` reads host-wide provider account quotas through the configured OMP executable's `usage --json`, independent of session runtimes. It honors explicit `--profile` selection from Gateway configuration and requires pairing authentication. Concurrent requests are serialized; successful results are cached for 30 seconds. The subprocess has a 30-second timeout and a 1 MiB stdout bound. Its process tree is terminated and reaped before the fetch slot is released, including after request cancellation. Failure returns HTTP 503 `usage_unavailable`, never an empty success.

The response is `{generatedAt,accounts:[{id,provider,accountLabel,plan,fetchedAt,status,limits}]}`. Account IDs are opaque; labels are masked. Account `status` is `available`, `unavailable` (including OMP accounts without usage), or `disabled`. Each limit contains `{id,label,modelId,tier,windowLabel,resetsAt,usedFraction,used,limit,remaining,unit,status}`. Optional facts are null; timestamps are epoch milliseconds and fractions are ratios (0.62 means 62%). Limit status is OMP's `ok`, `warning`, `exhausted`, or `unknown`. `generatedAt` describes snapshot generation; `fetchedAt` describes the provider report's freshness. Missing quotas never mean zero usage.
