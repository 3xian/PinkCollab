# PinkCollab Gateway API v2

This API is independent of OMP's RPC transport version.

## Authentication and errors

`POST /api/v2/pair` accepts `{token,name}` and returns `{clientId,credential,host,protocolVersion:2}`. The token is single use and expires after five minutes. Every other v2 route, including WebSocket upgrade, requires `Authorization: Bearer <credential>`. Credentials are never accepted in a URL. Requests with an `Origin` header are rejected. Host-side revocation invalidates the credential.

V2 errors use `{code,message}`. Codes include `authentication_required`, `workspace_forbidden`, `session_not_found`, `runtime_required`, `session_busy`, `input_expired`, `idempotency_conflict`, `invalid_file`, `upload_failed`, `stale_cursor`, `history_unavailable`, `unsupported_capability`, and `persistence_unavailable`. Unconfirmed external effects appear as an Operation with `status: outcome_unknown`.

## REST resources

| Method | Path | Result |
| --- | --- | --- |
| GET | `/api/v2/host` | `{host,protocolVersion:2,capabilities}`; `fileUploads:true` when supported |
| GET | `/api/v2/workspaces` | `{workspaces:[...]}` |
| GET | `/api/v2/fs/list?path=...` | Directory listing within configured roots |
| POST | `/api/v2/sessions` | Idempotently creates a persistent session; does not start OMP |
| GET | `/api/v2/sessions?limit=50&cursor=...` | `{sessions:[{session,runtime}],nextCursor}`; creation-order keyset, limit 1–100 |
| GET | `/api/v2/sessions/:id` | `{session,runtime,messages,recentOperations,historyRef}` |
| POST | `/api/v2/sessions/:id/commands` | `{operation,receiptStored}`; 202 for new, 200 for replay |
| PUT | `/api/v2/sessions/:id/files/:fileId?name=...` | Upload raw bytes; `{fileId,name,size}` |
| GET | `/api/v2/sessions/:id/operations/:commandId` | The authenticated client's durable receipt |
| GET | `/api/v2/sessions/:id/models` | `{models,thinkingLevels}` for an attached runtime |
| GET | `/api/v2/sessions/:id/history?limit=50&cursor=...` | `{items,source,nextCursor}`; limit 1–100 |
| GET | `/api/v2/events` | WebSocket resource subscriptions |

Creation body: `{commandId,hostId,cwd,title?}`. The Gateway canonicalizes and checks `cwd`. Creation idempotency is scoped to `(clientId,commandId)`; the session row and receipt commit together. The same key with different semantic input returns `idempotency_conflict`. A successful create returns the SessionRecord directly. `engineSessionRef` is private and never accepted from a client.

`SessionRecord` owns `id`, `hostId`, `cwd`, `title`, `metadataRevision`, `createdAt`, `updatedAt`, and `archivedAt`. It contains no runtime status. `runtime: null` means no attached process. An attached runtime has a unique `generation`, `phase` (`starting`, `ready`, `stopping`), `execution` (`unknown`, `active`, `quiescent`), `actualModel`, and `pendingInputs`. Completion of one prompt does not detach the runtime or end the conversation.

## Commands and receipts

Every command has a client-generated `commandId`, unique within `(clientId,sessionId)`. Retrying the same ID and semantic body returns the current receipt without another dispatch. Receipts are retained with the session management record. Android stores each pending command and its encrypted payload in a separate SQLite row before sending it. A conditional update claims the row before POST, so an unsent row deleted by a newer draft cannot later be posted by recovery. On app restart Android queries pending receipts and, when safe, resends only the original ID and payload. Each prompt draft has a stable intent ID used directly as its `commandId`: even when a terminal receipt removes the local row, an older restored draft checks that ID on the Gateway before posting. Android checks unresolved earlier prompts before sending an edited draft. A Stop whose response may have been lost remains lookup-only and is never automatically resent. During receipt lookup, only an exact `operation_not_found` response counts as absence; other failures preserve the pending command.

```json
{"commandId":"example-1","type":"prompt","delivery":"start","message":"Check the build"}
```

| Type | Additional fields | Meaning |
| --- | --- | --- |
| `start_runtime` | none | Attach an idle OMP process without a prompt |
| `prompt` | `delivery`, `message`, `fileIds?`, `expectedGeneration?` | `start` needs a settled runtime or starts one lazily; `steer` and `follow_up` target an active generation |
| `interrupt` | `expectedGeneration` | Ask OMP to abort current execution; keep the process |
| `stop_runtime` | `expectedGeneration` | Stop and confirm process exit; a database failure may return `receiptStored:false` |
| `respond` | `expectedGeneration`, `inputRequestId`, and `value` / `confirmed` / `cancelled` | Answer one pending OMP input request |
| `select_model` | `expectedGeneration`, `provider`, `modelId` | Select an OMP model without local role or Ctrl+P interpretation |
| `set_thinking_level` | `expectedGeneration`, `level` | Set thinking independently; actual state comes from OMP |

Operations progress through `accepted`, `dispatching`, optionally `running`, then `succeeded`, `failed`, `cancelled`, or `outcome_unknown`. `accepted` means a required receipt was persisted, not that OMP received the command. `dispatching` is saved before the external send boundary. A Stop response with `receiptStored:false` means termination was requested but no durable receipt exists; clients must verify the runtime state instead of resending Stop automatically. After a Gateway restart, accepted commands are cancelled and dispatching/running commands become outcome unknown; neither is automatically replayed. A late OMP acknowledgement cannot turn an already settled execution back into `active`. The Gateway cannot promise exactly once execution across a crash. `runtimeGeneration` and `commandId` serve different purposes.

### File uploads

Upload up to five files before submitting a prompt. A `fileId` is `file_` followed by 32 hex digits; retrying a PUT with the same ID, name, and bytes returns the same file. Each file must be 1 byte to 10 MiB. The raw request body is `application/octet-stream`, and `name` is a URL query parameter containing a filename without path separators. Files are published atomically under the Gateway data directory at `uploads/<sessionId>/<fileId>/<name>`, private to the Gateway OS user. `fileIds` in a prompt may refer only to files uploaded for that session. A prompt may contain files with an empty `message`.

For a settled `start` prompt, the Gateway adds quoted `@` file mentions with absolute host paths to OMP's RPC `prompt.message`. OMP reads those mentions into the new turn, subject to its own file-type and size rules. Running `steer` and `follow_up` prompts enter OMP's queue, where file mentions are not auto-read; the Gateway passes plain host paths for its tools and attaches recognized PNG, JPEG, GIF, or WebP images through OMP's `images: ImageContent[]` field up to 512 KiB total per prompt. Larger queued images remain available through their host paths. An RPC frame over the 1 MiB transport limit fails explicitly. OMP RPC has no generic binary attachment field; `@file` command-line arguments are unavailable in RPC mode, although `@` mentions inside direct prompt text work. Uploaded files remain on the host until removed from the Gateway data directory.

## WebSocket synchronization

The server sends a `host/sessions` snapshot when the socket opens. A client may send `{"type":"subscribe","resource":"session/<id>"}` or `{"type":"unsubscribe","resource":"session/<id>"}`. Session detail is sent only for subscribed sessions. Each snapshot has `subscriptionId`, `resource`, `cursor:{epoch,revision}`, and `payload`. Changes have the same subscription and resource plus `epoch`, `baseRevision`, `revision`, and domain `changes`.

Apply a change only when its subscription and epoch match the current snapshot and `baseRevision` equals the current revision. Ignore an already applied revision. A gap, epoch change, or `resync_required` requires a fresh subscription and snapshot. Reconnect replaces the live projection; the server does not replay events persistently. Snapshot registration precedes reading the view, and changes queued after its cursor follow the snapshot. Slow clients and oversized changes are told to resynchronize. The host list and each session detail have separate cursors; they are not a global transaction.

Live messages carry IDs scoped to a runtime generation. `v2.timeline.reset` clears the live tail when a runtime starts; `v2.timeline.patch` upserts `items` by ID and removes `removedIds`. The Gateway coalesces display changes briefly and bounds each patch. Under backpressure it may skip replaceable text deltas, while the final OMP message restores the complete live preview. A final message replaces the matching draft, and older deltas cannot append to it. The live preview is bounded; full durable text is read from OMP history. A runtime in `stopping` rejects new commands until its process tree exit is confirmed.

## History and recovery

OMP's JSONL transcript is the authority. The history source identifies the current branch leaf and content. A cursor binds to that source; if the file or branch changes, the server returns `stale_cursor` and the client restarts pagination. A missing or corrupt mapped file returns `history_unavailable`. A newly created session with no OMP mapping returns an empty page with `source:null`. `nextCursor:null` means pagination is complete.

History pages and live WebSocket updates are separate reads without cross-source atomicity. Android offers a separate saved-history view while attached and pages history automatically when detached. It does not merge OMP file entries into the live tail by guessing whether their text or IDs match. Resume uses the stored server-side OMP mapping, starts a new runtime generation, and never replays old Operations.
