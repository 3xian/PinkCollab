package dev.pinkcollab.ui

import android.net.Uri
import dev.pinkcollab.data.AttentionResponse
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.OperationReceipt
import dev.pinkcollab.data.OperationStatus
import dev.pinkcollab.data.RuntimeExecution
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.SessionStatus

internal data class SessionPageState(
    val detail: LoadState<SessionDetail>,
    val host: HostState?,
    val draft: SessionDraft,
    val selectingFiles: Int,
    val activity: SessionActivity,
    val model: LoadState<ModelCatalog>?,
)

internal sealed interface SessionAction {
    data object Retry : SessionAction
    data object Send : SessionAction
    data class DraftChanged(val text: String) : SessionAction
    data class FileSelected(val uri: Uri) : SessionAction
    data class FileRemoved(val id: String) : SessionAction
    data class Command(val command: SessionUserCommand) : SessionAction
    data class Respond(val response: AttentionResponse) : SessionAction
    data class LoadModels(val force: Boolean) : SessionAction
    data class SelectModel(val model: ModelInfo) : SessionAction
    data class SetThinkingLevel(val level: String) : SessionAction
    data object LoadSavedHistory : SessionAction
    data object LoadEarlierHistory : SessionAction
}

internal data class SessionControlsState(
    val attached: Boolean,
    val historyMode: Boolean,
    val inputEnabled: Boolean,
    val canSend: Boolean,
    val canAttach: Boolean,
    val canChooseModel: Boolean,
    val canStart: Boolean,
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
    showSavedHistory: Boolean,
): SessionControlsState {
    val session = detail.session
    val connected = host?.connected == true
    val attached = session.runtimeAttached && connected
    val inputEnabled = connected && !activity.inputBusy && session.attention == null &&
        session.status != SessionStatus.Starting && session.status != SessionStatus.Stopping &&
        (!session.runtimeAttached || session.runtimeExecution != RuntimeExecution.Unknown)
    val visibleItems = visibleSessionItems(detail, showSavedHistory)
    val placeholder = when {
        visibleItems.isEmpty() -> "What should OMP do?"
        session.status == SessionStatus.Running -> "Steer OMP…"
        else -> "Send another prompt…"
    }
    return SessionControlsState(
        attached = attached,
        historyMode = showSavedHistory && session.runtimeAttached,
        inputEnabled = inputEnabled,
        canSend = (draft.text.isNotBlank() || draft.files.isNotEmpty()) && inputEnabled && selectingFiles == 0,
        canAttach = inputEnabled && draft.files.size + selectingFiles < 5,
        canChooseModel = attached && !activity.inputBusy,
        canStart = !session.runtimeAttached && connected && !activity.inputBusy,
        canInterrupt = attached && !activity.control && session.status in setOf(SessionStatus.Running, SessionStatus.NeedsInput),
        canStop = attached && !activity.control,
        placeholder = placeholder,
    )
}

internal fun operationStatusText(receipt: OperationReceipt): String = when (receipt.status) {
    OperationStatus.Accepted -> "${receipt.commandType}: received by Gateway"
    OperationStatus.Dispatching -> "${receipt.commandType}: sending to OMP"
    OperationStatus.Running -> "${receipt.commandType}: running"
    OperationStatus.OutcomeUnknown -> "${receipt.commandType}: outcome unconfirmed; check this session before retrying"
    OperationStatus.Failed -> "${receipt.commandType}: ${receipt.errorCode ?: "failed"}"
    OperationStatus.Cancelled -> "${receipt.commandType}: cancelled"
    OperationStatus.Succeeded -> "${receipt.commandType}: succeeded"
    is OperationStatus.Unknown -> "${receipt.commandType}: ${receipt.status.wire}"
}
