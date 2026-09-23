# Daily use

This page is for operating sessions after the Gateway is paired. It describes what the Android app actually does. API calls that have no button are named as API-only.

For the first install, use [Get started](getting-started.md). For HTTPS, revocation, and a background service, use [Deployment](deployment.md).

## Contents

- [Create a session](#create-a-session)
- [Watch it](#watch-it)
- [Add instructions](#add-instructions)
- [Answer a question](#answer-a-question)
- [Change the model](#change-the-model)
- [Interrupt and Stop](#interrupt-and-stop)
- [Several hosts](#several-hosts)
- [After a turn finishes](#after-a-turn-finishes)
- [After a disconnect or restart](#after-a-disconnect-or-restart)
- [What the app does not expose](#what-the-app-does-not-expose)

<a id="create-a-task"></a>
## Create a session

Open **Workspaces**, pick a paired host, and open an allowed directory. The browser lists child directories. It hides dot-directories and does not run Git or other project inspection. Tap **Create session here**.

The app sends `POST /api/v1/sessions` with the host id and directory only. The Gateway starts `omp --mode rpc-ui` in that directory and leaves the session idle until you send a prompt. The title comes from the directory name until a prompt supplies one.

You cannot point the app at an OMP process you already started in a terminal. PinkCollab manages sessions its Gateway starts.

The directory must sit under a workspace root from `config.yaml`. That allowlist is the Gateway's browse and create boundary. It is not an OS sandbox for OMP.

## Watch it

Sessions from every paired host appear in one pager, newest first. A page shows the title, status, and host name. Opening a session loads its timeline.

Status labels in the app:

| Status | Label | What it means for you |
| --- | --- | --- |
| `starting` | Starting | OMP is launching. Commands are not useful yet. |
| `running` | Running | A turn is in progress. You can steer, interrupt, or stop. |
| `needs_input` | Needs you | OMP is waiting for an answer. The composer stays disabled until you answer or cancel. |
| `idle` | Paused | The runtime is still attached, and no turn is running. Send another prompt, or stop. |
| `completed` | Completed | The agent run ended. The process may still be attached. See below. |
| `failed` | Failed | The turn or process failed. If the runtime is gone, you cannot prompt this session. |
| `stopped` | Stopped | You stopped the process, or it exited outside a completed or failed run. No further commands. |
| `offline` | Offline | The Gateway restarted while this session was live. The process was not restored. |

`runtimeAttached` is the field that decides whether commands can still reach OMP. The app enables the composer only when that flag is true, the host is connected, and no question is pending. The activity string (for example "Interrupted") is not shown; the status label is.

Live replies and tool activity update while the WebSocket is connected. The phone does not keep a private copy of the transcript. Force-closing the app drops the in-memory view; reopening it loads a snapshot and, when needed, the session detail again.

## Add instructions

Type in the composer and tap **Send**. The button label does not change. The placeholder does:

- empty session: "What should OMP do?"
- status `running`: "Steer OMP…"
- otherwise: "Send another prompt…"

The Gateway, not the app, sets OMP `streamingBehavior` to `steer` when the status is `running`. Any other attached status starts a new turn. You cannot send a prompt while a question is unanswered.

Steering is useful only while a turn is running. If the session has already completed or gone idle, you are starting another turn, not nudging the one that finished.

## Answer a question

An attention card appears only when this session has a pending input request. The first-session example will not always produce one.

The card covers the request types OMP sends:

- **select** — one button per option. The value must match an option exactly.
- **confirm** — Confirm or Decline.
- **input** and **editor** — a text field. Editor uses a taller field.
- **Cancel** is always available.

After a successful answer the session returns to running. A stale or mismatched request id is rejected; answer the card that is on screen, or reopen the session to refresh it.

## Change the model

**Model** sits in the composer row with Interrupt, Stop, and Send. It is enabled while a runtime is attached. The sheet is a single-choice list in OMP's Ctrl+P order, including role and thinking level. Choosing an entry calls the model-selection API with provider, id, and role. The app does not expose the legacy cycle endpoint.

The list is empty when OMP has no Ctrl+P models configured. Model credentials stay in OMP's host configuration. The phone never asks for them.

## Interrupt and Stop

These are different operations. Do not treat them as two labels for the same stop.

**Interrupt** is enabled while the status is `running` or `needs_input` and the runtime is attached. There is no confirmation dialog. It sends OMP `abort`, moves the session to idle (the app label is Paused), clears a pending question, and leaves the process running. You can send another prompt.

**Stop** is enabled whenever the runtime is attached. The app asks you to confirm. It closes OMP's stdin and terminates the process if it has not exited after three seconds. The session becomes `stopped`, the runtime is detached, and Model, Interrupt, Stop, and Send are disabled. You cannot resume that process. The transcript stays visible.

A Gateway shutdown also stops the OMP processes that Gateway started. That is a host-side stop, not an Interrupt.

## Several hosts

Pair each computer separately. Android lists their sessions in one pager and keeps a connection per host. A host card shows whether that connection is online, reconnecting, offline, or needs pairing again.

Removing a host in the app deletes the saved connection on the phone. The dialog says the host itself will not be changed. To revoke access, run `pinkcollab clients` and `pinkcollab revoke --client <clientId>` on that host. See [pairing and security](deployment.md#pairing-and-security).

There is no multi-user permission model. Each Gateway has one host identity and a list of paired devices.

## After a turn finishes

`completed` means OMP reported `agent_end`. It does not mean the process exited, and it does not by itself free a `max_sessions` slot.

If `runtimeAttached` is still true, Send another prompt. Interrupt stays disabled because the status is no longer `running`. Stop still works.

If the process has exited, the runtime is detached and the composer stays disabled. Create a new session to continue in that project. The old transcript remains readable when the session file is still on the host.

`max_sessions` counts a starting session and every session whose OMP process is still alive. A completed session with a live process still counts. A stopped, failed, or completed session whose process has exited does not. The default limit is 8. The authoritative rule is in [reference](reference.md#max_sessions).

## After a disconnect or restart

These are not the same event.

**The phone loses the network, or you leave the app.** The OMP process keeps running on the host. When the app returns to the foreground, or the network comes back, it reconnects, takes a new snapshot, and reloads an open session. There is no replay of events missed while disconnected, and no system notification that a question arrived. Look at the session.

**The Gateway process restarts.** Running OMP processes are not reattached. Sessions that were `starting`, `running`, `needs_input`, or `idle` become `offline`, with `runtimeAttached` false. `completed`, `failed`, and `stopped` keep those statuses, also detached. Pairings and session metadata remain. History is read on demand from OMP's session file for the current branch, including tool calls and results that file still holds, up to the same 500-item retention as the live timeline. That is not the live event stream. Message deltas, sequence numbers, and anything already trimmed are gone. If the session file is missing, the detail can be empty.

**The host sleeps or shuts down.** PinkCollab does not keep a session running across that. Sleep suspends the machine's processes; shutdown ends them. On the next Gateway start, a session that was live shows as offline. Do not read "history is still there" as "the agent kept working."

A phone-side cache of the transcript is not part of recovery. Only the pairing credential is stored on the device, encrypted by Android. Provider credentials are not.

## What the app does not expose

- Delete a session. `DELETE /api/v1/sessions/:id` exists and removes Gateway metadata only after the runtime is gone. It never deletes OMP's session files. The app has no delete control.
- Cycle the model with the legacy endpoint. Use the model sheet.
- Revoke a phone. That is a host command.
- Attach a terminal session, open a shell, edit code, or operate Git.
- Background notifications. A waiting input is visible in the open app only.

Protocol details for those API calls are in [protocol](protocol.md).
