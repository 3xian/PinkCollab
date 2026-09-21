# PinkCollab Protocol v1

Android depends only on this protocol. The OMP adapter follows the [official RPC specification](https://github.com/can1357/oh-my-pi/blob/main/docs/rpc.md) and [rpc-types.ts](https://github.com/can1357/oh-my-pi/blob/main/packages/coding-agent/src/modes/rpc/rpc-types.ts). It currently uses v1 NDJSON (up to 1 MiB per frame) and does not negotiate OMP v2 chunk transport.

Except for pairing, every REST request and WebSocket handshake must include `Authorization: Bearer <credential>`. Credentials cannot be passed through URL query parameters, and browser cross-origin access is not enabled. All responses include `Cache-Control: no-store`.

## REST

| Method | Path | Request / response |
| --- | --- | --- |
| POST | `/api/v1/pair` | `{token,name}` → `{clientId,credential,host,protocolVersion}`, 201 |
| GET | `/api/v1/host` | Local Host |
| GET | `/api/v1/workspaces` | `[{name,path}]` |
| GET | `/api/v1/fs/list?path=...` | `{path,parent?,directories:[{name,path}],git?:{branch,status}}` |
| GET | `/api/v1/sessions` | Session array, sorted by update time in descending order |
| GET | `/api/v1/sessions/:id` | `{session,timeline,model?}` |
| POST | `/api/v1/sessions` | `{hostId,cwd,prompt?,title?}` → Session, 201. An omitted or blank prompt starts an attached, idle OMP task. |
| DELETE | `/api/v1/sessions/:id` | Deletes management metadata only when no runtime remains, 204; does not delete original OMP data |
| POST | `/api/v1/sessions/:id/prompt` | `{message}`; automatically sets OMP streamingBehavior=steer while running |
| POST | `/api/v1/sessions/:id/interrupt` | No request fields; sends OMP abort and transitions to idle |
| POST | `/api/v1/sessions/:id/stop` | No request fields; closes stdin and terminates OMP if it has not exited after 3 seconds |
| POST | `/api/v1/sessions/:id/respond` | See input responses below |
| GET | `/api/v1/sessions/:id/models` | Returns `{models:[{provider,id,name,role,thinkingLevel?}]}` in OMP's Ctrl+P cycle order |
| POST | `/api/v1/sessions/:id/model` | Selects one cycle entry using `{provider,id,role}` and returns `{model:{provider,id,name}}` |
| POST | `/api/v1/sessions/:id/model/cycle` | Legacy model-cycle command; returns `{model:{provider,id,name}}` |

Unless the table specifies a response, successful commands return `{ok:true}`. Rejected commands return 409, rejected session creation returns 422, out-of-bounds directories return 403, and authentication failures return 401. Business errors return `{error:string}`; the HTTP framework returns 400/415/422 for JSON decoding errors. Request bodies are limited to 512 KiB, and prompts and input values to 256 KiB.

The Gateway owns model state: `GET /api/v1/sessions/:id` carries it as `model`, which is present only while a runtime is attached and OMP reports a model. Clients must treat `model` as optional and follow `model.updated` events for changes they did not initiate. Listing models resolves OMP's effective `modelRoles` and `cycleOrder` against `get_available_models`, matching the choices and order used by Ctrl+P. Selecting a choice calls `set_model` and applies that role's configured thinking level. The role is required so duplicate model targets in the cycle remain unambiguous. The cycle endpoint remains for older clients and is rejected with 409 when OMP reports no alternative model.

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

Statuses: starting, running, needs_input, idle, completed, failed, stopped, offline. Only `agent_end` completes the entire agent execution; `turn_end` does not end the task. `runtimeAttached` indicates whether commands can still be sent to the runtime. A completed task may still have a runtime; this value becomes false after the Gateway restarts.

When input is requested, the session includes `attention: {id,type,text,options}`. `type` is select / confirm / input / editor, and `needsAttention=true`. Responses must include the original request id; expired or mismatched ids are rejected.

```json
{"id":"request-id","value":"selected option or input text"}
```

```json
{"id":"request-id","confirmed":true}
```

```json
{"id":"request-id","cancelled":true}
```

For select requests, value must be an exact string from options. After input is resolved, the session returns to running. Prompts cannot be sent while an input request remains unanswered.

## WebSocket

After a connection to `/api/v1/events`, the Gateway registers the subscription before sending a snapshot, eliminating the gap between the initial REST state and the incremental event stream.

```json
{
  "sequence": 0,
  "type": "snapshot",
  "timestamp": "2026-09-14T08:00:00Z",
  "payload": {"host":{},"sessions":[],"protocolVersion":1}
}
```

Subsequent events use `{sequence,type,timestamp,payload}`. sequence is an increasing number within the current Gateway process; snapshots use 0.

| type | payload |
| --- | --- |
| session.updated | Full Session |
| session.deleted | `{sessionId}` |
| timeline.updated | `{sessionId,item:{id,kind,text,detail,tool?,timestamp}}` |
| message.delta | `{sessionId,text}`; text delta for the current assistant message |
| model.updated | `{sessionId,model}`; `model` may be null when OMP has no active model |
| attention.created | `{sessionId,attention}` |

Clients merge events by session/item id and use updatedAt to prevent older states queued after the snapshot from overwriting newer states. Timeline updates with the same id are upserts; the final assistant message replaces the current streaming draft. Timeline kind is user / assistant / tool / subagent / error / notice. Tool items may include `tool:{callId,name,arguments,result,isError,completed}`: `arguments` is the original JSON object when the call itself was observed, and null when only its result could be reconstructed from history. `detail` stays empty for tool items, so the payload travels once, inside `tool`. Start and result updates share `callId` and retain the call timestamp, allowing clients to project the trace without parsing display text.

The Gateway sends a Ping every 25 seconds and disconnects if no heartbeat response is received for 70 seconds. Slow clients are disconnected when their backlog exceeds 256 events. Clients should reconnect to obtain a snapshot and reload the details of any open task. This version does not provide persistent event replay.

## Data ownership

SQLite stores only the Host ID, client token hashes, pairing token hashes, Session management metadata, and OMP session file location information. OMP manages provider credentials. Up to 500 live Timeline items are kept in memory, preferentially retaining user-visible message boundaries over hidden tool bookkeeping. Historical conversations, including paired tool calls/results, are reconstructed for the current branch using parentId from OMP entries with the same retention policy.
