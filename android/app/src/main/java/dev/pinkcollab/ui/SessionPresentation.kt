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

/** Terminal icon fonts use private-use code points that Android cannot render. */
internal fun sessionDisplayTitle(title: String): String = buildString {
    title.codePoints().forEach { codePoint ->
        if (Character.getType(codePoint) != Character.PRIVATE_USE.toInt()) appendCodePoint(codePoint)
    }
}.trim().ifEmpty { "Untitled session" }

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
    val commands: LoadState<SessionCommands>? = null,
)

/** Connection recovery waits for a fresh snapshot; old request errors no longer describe it. */
internal fun sessionHistoryError(detail: SessionDetail, host: HostState?, refreshError: String?): String? {
    if (host?.connected != true) return null
    if (detail.snapshotToken == null) return refreshError
    return if (detail.savedHistory == SavedHistory.Failed) "Could not load message history" else null
}

internal sealed interface SessionAction {
    data object Send : SessionAction
    data class DraftChanged(val text: String) : SessionAction
    data class FileSelected(val uri: Uri) : SessionAction
    data class FileRemoved(val id: String) : SessionAction
    data class Command(val command: SessionUserCommand) : SessionAction
    data class Respond(val response: AttentionResponse) : SessionAction
    data class LoadModels(val force: Boolean) : SessionAction
    data class LoadCommands(val force: Boolean = false) : SessionAction
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
)

internal fun sessionControls(
    detail: SessionDetail,
    host: HostState?,
    draft: SessionDraft,
    selectingFiles: Int,
    activity: SessionActivity,
): SessionControlsState {
    val session = detail.session
    val connected = host?.connected == true && detail.snapshotToken != null
    val attached = session.runtimeAttached && connected
    val inputEnabled = !activity.inputBusy && (!connected || (session.attention == null &&
        session.status != SessionStatus.Starting && session.status != SessionStatus.Stopping))
    return SessionControlsState(
        attached = attached,
        inputEnabled = inputEnabled,
        canSend = connected && (draft.text.isNotBlank() || draft.files.isNotEmpty()) &&
            inputEnabled && selectingFiles == 0,
        canAttach = inputEnabled && draft.files.size + selectingFiles < 5,
        canChooseModel = connected && (attached || (!session.runtimeAttached && inputEnabled)) &&
            session.status != SessionStatus.Starting && session.status != SessionStatus.Stopping &&
            !activity.inputBusy,
        canInterrupt = attached && !activity.control && session.status in setOf(SessionStatus.Running, SessionStatus.NeedsInput),
        canStop = attached && !activity.control,
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
