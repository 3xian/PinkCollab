# Daily use

Install and pair as described in the [README](../README.md). The phone keeps its Gateway credential encrypted with Android Keystore; provider credentials stay in OMP on the host.

At launch, the animated logo sits over a pulsing pink glow while the app waits for its initial session list.

## Create and continue a session

Open **Workspaces**, choose a paired host and an allowed directory, then tap **Create session here**. Creation saves a conversation entry without starting OMP or using a process slot. The first prompt starts OMP lazily. **Start** attaches OMP without sending a prompt. Each later prompt can continue the same conversation; an attached process is not required for the conversation to remain available.

When the runtime is working, sending another prompt steers that execution. When OMP confirms it is settled, sending starts a new turn. **Interrupt** asks OMP to abort current work and keeps the process. **Stop** ends the process after confirmation; it preserves the Session and OMP transcript. Sending another prompt after Stop starts a new generation and loads that transcript. A stale Stop or answer cannot target a newer generation.

Tap the paperclip in a session to choose up to five files, then send them with a message or by themselves. Each file can be up to 10 MiB. Files are copied to the Gateway host under its private data directory. For a new turn, OMP reads the uploaded files through file mentions in the prompt. If a prompt is sent while OMP is working, it receives the host paths and small images directly; it can open other files from those paths. The uploaded copies remain on the host after the session ends.

`max_sessions` counts process slots from startup until confirmed process exit, including an attached process whose latest turn has finished. If the limit is reached, stop an attached runtime that is no longer needed. Creating a Session does not consume a slot.

## Live page and history

The session page subscribes to a versioned live view. It shows OMP output, model state, pending questions, and command receipts. `accepted` means Gateway stored the command; `dispatching` means it is entering OMP; `running` means execution is underway. If the result is `outcome_unknown`, inspect the conversation before issuing a new command. After an uncertain network response or server error, retrying the same action in the running app first checks the original receipt, then resends the original command ID and payload only when safe. If Stop was sent without a stored receipt, inspect the runtime before trying again. Pending commands are not preserved across app restarts.

The live projection is a bounded preview. While attached, **Saved history** opens a separate view of the durable OMP transcript and **Back to live** returns to current output. After the runtime detaches, the app reads saved history automatically. **Load earlier messages** retrieves older pages. A missing or corrupt history file is reported as unavailable. The app does not guess that a live message and a transcript entry are identical based on their text. The live view and saved history can be observed at different moments.

An input card presents OMP's select, confirm, input, or editor request. Only one answer to a request is accepted. The model sheet lists models reported by OMP and separately offers thinking levels. It has no Ctrl+P role or cycle-order rules. Model controls require an attached runtime; opening history does not start one.

## Disconnects

A phone disconnect does not stop OMP. Reconnecting replaces the list and open-session live views with fresh subscription snapshots. The app does not queue offline prompts. If the Gateway restarts, it does not reattach old processes; the Session and OMP transcript remain. Starting that Session later creates a new runtime generation. An unclean exit can leave a conservative runtime lease. Run `pinkcollab leases`; after stopping the Gateway and verifying the old OMP process and descendants are gone, run `pinkcollab clear-lease --session <id> --generation <generation> --verified-exited`. The exact generation prevents clearing a newer lease by mistake.

In **Workspaces**, a host shows **Connecting…** while establishing, synchronizing, or automatically retrying its connection. Its directories cannot be opened until it is online.

Removing a host in Android deletes the phone's local connection only. To revoke that device on the Gateway, run `pinkcollab clients` and `pinkcollab revoke --client <clientId>` on the host.
