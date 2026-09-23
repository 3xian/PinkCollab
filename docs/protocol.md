# PinkCollab protocol v1

HTTP and WebSocket contract for a client or adapter. Android is the only client in this repository; it depends on this document, not on Gateway internals.

Two version numbers appear in a session. They are not interchangeable.

| Version | What it versions | Current value |
| --- | --- | --- |
| Gateway API protocol | Pairing response and the WebSocket snapshot field `protocolVersion` | `1` |
| OMP RPC transport | NDJSON frames between the Gateway and `omp --mode rpc-ui` | v1, up to 1 MiB per frame. The adapter does not negotiate OMP v2 chunk transport. |

The OMP adapter follows the [official RPC specification](https://github.com/can1357/oh-my-pi/blob/main/docs/rpc.md) and [rpc-types.ts](https://github.com/can1357/oh-my-pi/blob/main/packages/coding-agent/src/modes/rpc/rpc-types.ts).

## Contents

- [Authentication](#authentication)
- [REST](#rest)
- [Models](#models)
- [Session](#session)
- [Input responses](#input-responses)
- [WebSocket](#websocket)
- [Timeline items](#timeline-items)
- [Data ownership](#data-ownership)
- [Adapter limits](#adapter-limits)

## Authentication

Except for pairing, every REST request and WebSocket handshake must include `Authorization: Bearer <credential>`. Credentials cannot be passed through URL query parameters. A request that includes an `Origin` header is rejected with 403 and `browser origins are not supported`. Browser cross-origin access is not enabled.

All responses include `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.

The pairing token is 48 hex characters. The client name must be non-empty and at most 100 characters. A token lasts five minutes and is deleted on the successful pair that consumes it.

## REST

| Method | Path | Request / response |
| --- | --- | --- |
| POST | `/api/v1/pair` | `{token,name}` → `{clientId,credential,host,protocolVersion}`, 201. Unknown fields are rejected. |
| GET | `/api/v1/host` | Local Host |
| GET | `/api/v1/workspaces` | `[{name,path}]` |
| GET | `/api/v1/fs/list?path=...` | `{path,parent?,directories:[{name,path}]}`. Dot-directories are omitted. A symlink whose target escapes the allowlist is omitted. There is no `git` field. |
| GET | `/api/v1/sessions` | Session array, sorted by `updatedAt` descending |
| GET | `/api/v1/sessions/:id` | `{session,timeline,model?}`. Unknown id: 404. |
| POST | `/api/v1/sessions` | `{hostId,cwd,prompt?,title?}` → Session, 201. An omitted or blank prompt starts an attached, idle OMP task. The Android app sends only `hostId` and `cwd`. |
| DELETE | `/api/v1/sessions/:id` | Deletes management metadata only when no runtime remains, 204. Does not delete original OMP data. Rejected while a runtime is attached or the task is still `starting`. Not exposed in the Android app. |
| POST | `/api/v1/sessions/:id/prompt` | `{message}`. Sets OMP `streamingBehavior=steer` while status is `running`. |
| POST | `/api/v1/sessions/:id/interrupt` | No request fields. Sends OMP `abort` and transitions to `idle`. The runtime stays attached. |
| POST | `/api/v1/sessions/:id/stop` | No request fields. Closes stdin and terminates OMP if it has not exited after 3 seconds. The runtime is detached. |
| POST | `/api/v1/sessions/:id/respond` | See [input responses](#input-responses). |
| GET | `/api/v1/sessions/:id/models` | `{models:[{provider,id,name,role,thinkingLevel?}]}` in OMP's Ctrl+P cycle order |
| POST | `/api/v1/sessions/:id/model` | Selects one cycle entry using `{provider,id,role}` and returns `{model:{provider,id,name,role,thinkingLevel?}}` |
| POST | `/api/v1/sessions/:id/model/cycle` | Legacy model-cycle command. Returns `{model:{provider,id,name,thinkingLevel?}}`. Role is omitted. Not used by the current Android app. |

Unless the table specifies a response, successful commands return `{ok:true}`.

| Condition | Status |
| --- | --- |
| Authentication failure, or invalid/expired pairing token | 401 |
| Malformed pairing body | 400 |
| Rejected command, including a missing runtime, a bad prompt, or a model that is not in the cycle | 409 |
| Rejected session creation, including a path outside the allowlist or `max_sessions` exceeded | 422 |
| Directory list outside the allowlist, or other browse refusal | 403 |
| Unknown session on `GET /api/v1/sessions/:id` | 404 |
| `Origin` header present | 403 |

Business errors return `{error:string}`. The HTTP framework returns 400/415/422 for JSON decoding and content-type errors. Request bodies are limited to 512 KiB. Prompts and input values are limited to 256 KiB (`1..262144` bytes for a prompt that is sent).

`hostId` on create must match this Gateway. `cwd` must be an absolute directory under a configured workspace root.

## Models

The Gateway owns model state. `GET /api/v1/sessions/:id` carries it as `model`, which is present only while a runtime is attached and OMP reports a model. Clients must treat `model` as optional and follow `model.updated` events for changes they did not initiate.

Listing models asks OMP for its merged effective settings, applies the running process's model flags, then resolves `modelRoles`, `cycleOrder`, and `modelProviderOrder` against `get_available_models`, matching the choices and order used by Ctrl+P. Selecting a choice calls `set_model`, applies that role's configured thinking level, and preserves the selected role plus OMP's actual thinking level in session state. The role is required so duplicate model targets in the cycle remain unambiguous. The cycle endpoint remains for older clients and is rejected with 409 when OMP reports no alternative model.

## Session

```json
{
  "id": "sess_xxx",
  "hostId": "host_xxx",
  "cwd": "/home/user/projects/shop",
  "title": "Fix checkout bug",
  "status": "running",
  "activity": "Testing",
  "needsAttention": false,
  "runtimeAttached": true,
  "createdAt": "2026-09-14T08:00:00Z",
  "updatedAt": "2026-09-14T08:00:30Z"
}
```

`sessionFile` is stored in SQLite and is not part of this object.

Statuses: `starting`, `running`, `needs_input`, `idle`, `completed`, `failed`, `stopped`, `offline`. Only `agent_end` completes the agent execution; `turn_end` does not end the task. A later `completed` does not overwrite an existing `failed`. An `agent_end` that arrives after Interrupt is recorded as `idle` instead.

`runtimeAttached` indicates whether commands can still be sent to the runtime. A completed task may still have a runtime. This value becomes false after the Gateway restarts, and when the process exits. `completed` is not a synonym for "process exited" and does not by itself free `max_sessions`. The quota counts a `starting` session and every session whose process is still alive.

When input is requested, the session includes `attention: {id,type,text,options}`. `type` is `select`, `confirm`, `input`, or `editor`, and `needsAttention` is true. Responses must include the original request id. Expired or mismatched ids are rejected.

## Input responses

```json
{"id":"request-id","value":"selected option or input text"}
```

```json
{"id":"request-id","confirmed":true}
```

```json
{"id":"request-id","cancelled":true}
```

For select requests, `value` must be an exact string from `options`. A confirm request requires `confirmed`. After input is resolved, the session returns to `running`. Prompts cannot be sent while an input request remains unanswered.

## WebSocket

Connect to `/api/v1/events` with the same `Authorization` header. Incoming WebSocket messages are limited to 1024 bytes; the server uses the socket for snapshots, events, and ping/pong, not for task commands. Task commands are REST.

The Gateway registers the subscription before sending a snapshot. This closes the gap between initial state and the incremental stream. Clients do not need a REST waterfall before the snapshot.

```json
{
  "sequence": 0,
  "type": "snapshot",
  "timestamp": "2026-09-14T08:00:00Z",
  "payload": {"host":{},"sessions":[],"workspaces":[],"protocolVersion":1}
}
```

Subsequent events use `{sequence,type,timestamp,payload}`. `sequence` increases within the current Gateway process. Snapshots use 0. There is no persistent replay.

| type | payload |
| --- | --- |
| `session.updated` | Full Session |
| `session.deleted` | `{sessionId}` |
| `timeline.updated` | `{sessionId,item:{id,kind,text,detail,tool?,timestamp}}` |
| `message.delta` | `{sessionId,text}` — text delta for the current assistant message |
| `model.updated` | `{sessionId,model}` — `model` may be null when OMP has no active model |
| `attention.created` | `{sessionId,attention}` |

`gateway.shutdown` is published internally during a graceful stop and closes the socket. Clients should treat the close as a disconnect, not as a renderable event.

Clients merge events by session and item id and use `updatedAt` to keep an older state, queued behind the snapshot, from overwriting a newer one. Reconnect to obtain a new snapshot, and reload the detail of any open task. This version does not provide persistent event replay.

The Gateway sends a Ping every 25 seconds and disconnects if no heartbeat response is received for 70 seconds. A credential revoked while the socket is open fails the next authentication check on that interval. Slow clients are disconnected when their backlog exceeds 256 events, which is also the broadcast channel capacity. A lagged receiver is dropped rather than resumed from the missed sequence.

## Timeline items

Timeline `kind` is `user`, `assistant`, `tool`, `subagent`, `error`, or `notice`.

Tool items may include `tool:{callId,name,arguments,result,isError,completed}`. `arguments` is the original JSON object when the call itself was observed, and null when only its result could be reconstructed from history. `detail` stays empty for tool items, so the payload travels once, inside `tool`. Start and result updates share `callId` and retain the call timestamp, so clients can project the trace without parsing display text. Timeline updates with the same id are upserts. The final assistant message replaces the current streaming draft.

Up to 500 timeline items are kept, whether the timeline is live or reconstructed. The oldest hidden tool bookkeeping is dropped before the oldest user, assistant, or error message.

## Data ownership

SQLite stores the host id, client token hashes, pairing token hashes, session management metadata, and the OMP session-file location. It does not store provider credentials or the transcript.

OMP manages provider credentials and the session file. Historical conversations, including paired tool calls and results, are reconstructed for the current branch by walking `parentId`, with the same 500-item retention policy. Reconstruction is not a replay of live events. A missing or unreadable session file yields an empty timeline.

The phone stores the Gateway credential and an in-memory view of tasks. It does not store provider credentials.

## Adapter limits

These bounds are PinkCollab's, applied to the OMP child process:

- One NDJSON frame, either direction, must stay under 1 MiB. A longer frame ends the session instead of being buffered without limit.
- A single stdin write must finish within 10 seconds.
- After Stop closes stdin, OMP has 3 seconds to exit before it is killed.
- Spawn waits up to 30 seconds for the ready frame.
- An RPC request such as prompt, abort, or `set_model` fails if OMP does not answer that request within 15 seconds. That timeout is not a limit on how long the agent may then run.

Next: [architecture](architecture.md) for how those stored rows behave across a restart, or [development](development.md) to build a client against this checkout.
