package dev.pinkcollab.ui

import android.net.Uri
import dev.pinkcollab.data.AttentionResponse
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.OperationReceipt
import dev.pinkcollab.data.OperationStatus
import dev.pinkcollab.data.SavedHistory
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionStatus
import dev.pinkcollab.data.TimelineItem

internal data class SessionPageState(
    val detail: LoadState<SessionDetail>,
    val host: HostState?,
    val draft: SessionDraft,
    val selectingFiles: Int,
    val activity: SessionActivity,
    val sendProgress: SendProgress?,
    val model: LoadState<ModelCatalog>?,
    val refreshError: String? = null,
    val historyItems: List<TimelineItem>? = null,
    val summary: Session? = null,
    val usage: LoadState<dev.pinkcollab.data.UsageSnapshot>? = null,
    val runtimeStart: RuntimeStartAttempt? = null,
)

/** Connection recovery waits for a fresh snapshot; old request errors no longer describe it. */
internal fun sessionHistoryError(detail: SessionDetail, host: HostState?, refreshError: String?): String? {
    if (host?.connected != true) return null
    if (detail.snapshotToken == null) return refreshError
    return if (detail.savedHistory == SavedHistory.Failed) "Could not load message history" else null
}

internal fun sessionSyncMessage(host: HostState?, hasSnapshot: Boolean): String = when (host?.connection) {
    ConnectionState.Connecting -> "Connecting to ${host.paired.host.name}…"
    ConnectionState.Reconnecting -> "Reconnecting to ${host.paired.host.name}…"
    ConnectionState.Synchronizing -> "Syncing sessions…"
    ConnectionState.AuthenticationRequired -> "Sign-in required. Open hosts to reconnect."
    ConnectionState.UpgradeRequired -> "Update required. Open hosts for details."
    is ConnectionState.Offline -> "Host offline. Waiting for a connection…"
    else -> if (hasSnapshot) "Loading message history…" else "Opening conversation…"
}

internal sealed interface SessionAction {
    data object Send : SessionAction
    data class DraftChanged(val text: String) : SessionAction
    data class FileSelected(val uri: Uri) : SessionAction
    data class FileRemoved(val id: String) : SessionAction
    data class Command(val command: SessionUserCommand) : SessionAction
    data class Respond(val response: AttentionResponse) : SessionAction
    data class LoadModels(val force: Boolean) : SessionAction
    data object LoadUsage : SessionAction
    data object LoadEarlierHistory : SessionAction
}

internal data class SessionControlsState(
    val attached: Boolean,
    val inputEnabled: Boolean,
    val canSend: Boolean,
    val canAttach: Boolean,
    val canChooseModel: Boolean,
    val canInterrupt: Boolean,
    val canStop: Boolean,
    val placeholder: String,
)

internal fun sessionControls(
    detail: SessionDetail,
    host: HostState?,
    draft: SessionDraft,
    selectingFiles: Int,
    activity: SessionActivity,
): SessionControlsState {
    val session = detail.session
    val connected = host?.connected == true
    val attached = session.runtimeAttached && connected
    val inputEnabled = connected && !activity.inputBusy && session.attention == null &&
        session.status != SessionStatus.Starting && session.status != SessionStatus.Stopping
    val hasMessages = detail.savedHistory.items.isNotEmpty() || detail.liveItems.isNotEmpty()
    val placeholder = when {
        !hasMessages -> "What should OMP do?"
        session.status == SessionStatus.Running -> "Steer OMP…"
        else -> "Send another prompt…"
    }
    return SessionControlsState(
        attached = attached,
        inputEnabled = inputEnabled,
        canSend = (draft.text.isNotBlank() || draft.files.isNotEmpty()) && inputEnabled && selectingFiles == 0,
        canAttach = inputEnabled && draft.files.size + selectingFiles < 5,
        canChooseModel = (attached || (!session.runtimeAttached && inputEnabled)) &&
            session.status != SessionStatus.Starting && session.status != SessionStatus.Stopping &&
            !activity.inputBusy,
        canInterrupt = attached && !activity.control && session.status in setOf(SessionStatus.Running, SessionStatus.NeedsInput),
        canStop = attached && !activity.control,
        placeholder = placeholder,
    )
}

internal fun operationStatusText(receipt: OperationReceipt): String? = when (receipt.status) {
    OperationStatus.OutcomeUnknown, is OperationStatus.Unknown ->
        "Could not confirm the result. Check the conversation before trying again."
    OperationStatus.Failed -> if (receipt.commandType == "prompt")
        "Your message could not be sent. Please try again."
        else "The action could not be completed. Please try again."
    else -> null
}
