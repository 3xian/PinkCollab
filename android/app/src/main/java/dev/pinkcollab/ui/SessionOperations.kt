package dev.pinkcollab.ui

import android.app.Application
import android.net.Uri
import dev.pinkcollab.data.GatewayRepository
import dev.pinkcollab.data.AttentionResponse
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.TerminalCommandFailure
import dev.pinkcollab.data.OperationStatus
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

internal interface SessionActions {
    suspend fun upload(session: Session, file: SelectedFile)
    suspend fun prompt(session: Session, message: String, fileIds: List<String>, intentId: String)
    suspend fun command(session: Session, command: SessionUserCommand)
    suspend fun respond(session: Session, response: AttentionResponse)
    suspend fun selectModel(session: Session, model: ModelInfo)
    suspend fun setThinkingLevel(session: Session, level: String)
    suspend fun setFastMode(session: Session, enabled: Boolean)
    suspend fun loadEarlierHistory(session: Session)
}

internal class RepositorySessionActions(
    private val application: Application,
    private val repository: GatewayRepository,
) : SessionActions {
    override suspend fun upload(session: Session, file: SelectedFile) =
        repository.uploadFile(application, session.hostId, session.id, file.id, file.name, Uri.parse(file.uri))

    override suspend fun prompt(session: Session, message: String, fileIds: List<String>, intentId: String) =
        repository.prompt(session.hostId, session.id, message, fileIds, intentId)

    override suspend fun command(session: Session, command: SessionUserCommand) =
        repository.command(session.hostId, session.id, command.wire)

    override suspend fun respond(session: Session, response: AttentionResponse) =
        repository.respond(session.hostId, session.id, response)

    override suspend fun selectModel(session: Session, model: ModelInfo) =
        repository.selectModel(session.hostId, session.id, model)

    override suspend fun setThinkingLevel(session: Session, level: String) =
        repository.setThinkingLevel(session.hostId, session.id, level)

    override suspend fun setFastMode(session: Session, enabled: Boolean) =
        repository.setFastMode(session.hostId, session.id, enabled)

    override suspend fun loadEarlierHistory(session: Session) = repository.loadEarlierHistory(session.hostId, session.id)
}

internal enum class SessionLane { Send, Action, History, Control }

internal enum class SessionUserCommand(val wire: String, val lane: SessionLane) {
    Start("start", SessionLane.Action),
    Interrupt("interrupt", SessionLane.Control),
    Stop("stop", SessionLane.Control),
}

internal sealed interface SendProgress {
    data class Uploading(val fileIndex: Int, val fileCount: Int, val fileName: String) : SendProgress
    data object Submitting : SendProgress
}

internal data class SessionOperationKey(val session: SessionKey, val lane: SessionLane)

internal sealed interface RuntimeStartAttempt {
    data class Pending(val previousReceiptId: String?) : RuntimeStartAttempt
    data class Failed(val message: String) : RuntimeStartAttempt
}

internal data class SessionActivity(
    val send: Boolean = false,
    val action: Boolean = false,
    val history: Boolean = false,
    val control: Boolean = false,
) {
    val inputBusy: Boolean get() = send || action || control
}

internal fun Set<SessionOperationKey>.activity(key: SessionKey) = SessionActivity(
    send = SessionOperationKey(key, SessionLane.Send) in this,
    action = SessionOperationKey(key, SessionLane.Action) in this,
    history = SessionOperationKey(key, SessionLane.History) in this,
    control = SessionOperationKey(key, SessionLane.Control) in this,
)

/** Owns the independent session lanes, including cancellation before Stop or Interrupt. */
internal class SessionOperations(
    private val scope: CoroutineScope,
    private val actions: SessionActions,
    private val drafts: SessionDraftStore,
    private val clearSentDraft: (SessionKey, Long) -> Unit,
    private val reportError: (String) -> Unit,
) {
    private val lock = Any()
    private val jobs = mutableMapOf<SessionOperationKey, Job>()
    private val mutableOperations = MutableStateFlow<Set<SessionOperationKey>>(emptySet())
    val operations = mutableOperations.asStateFlow()
    private val mutableSendProgress = MutableStateFlow<Map<SessionKey, SendProgress>>(emptyMap())
    val sendProgress = mutableSendProgress.asStateFlow()
    private var details: Map<SessionKey, SessionDetail> = emptyMap()
    private val mutableRuntimeStarts = MutableStateFlow<Map<SessionKey, RuntimeStartAttempt>>(emptyMap())
    val runtimeStarts = mutableRuntimeStarts.asStateFlow()

    /** A command acknowledgement can precede its runtime snapshot or terminal receipt. */
    fun updateDetails(current: Map<SessionKey, SessionDetail>) = synchronized(lock) {
        details = current
        mutableRuntimeStarts.update { attempts ->
            var updated: MutableMap<SessionKey, RuntimeStartAttempt>? = null
            for ((key, attempt) in attempts) {
                val detail = current[key]
                val receipt = detail?.operations?.lastOrNull { it.commandType == "start_runtime" }
                val resolved = when {
                    detail?.snapshotToken != null && detail.session.runtimeAttached &&
                        detail.session.status != SessionStatus.Starting &&
                        detail.session.status != SessionStatus.Stopping -> null
                    attempt is RuntimeStartAttempt.Pending && receipt != null &&
                        receipt.commandId != attempt.previousReceiptId -> when (receipt.status) {
                        OperationStatus.Failed, OperationStatus.Cancelled, OperationStatus.OutcomeUnknown,
                        is OperationStatus.Unknown -> RuntimeStartAttempt.Failed(
                            receipt.errorMessage ?: operationStatusText(receipt) ?: "Runtime start was cancelled.")
                        else -> attempt
                    }
                    else -> attempt
                }
                if (resolved != attempt) {
                    val changed = updated ?: attempts.toMutableMap().also { updated = it }
                    if (resolved == null) changed.remove(key) else changed[key] = resolved
                }
            }
            updated ?: attempts
        }
    }

    fun send(session: Session) {
        val key = SessionKey(session.hostId, session.id)
        val draft = drafts.state.value[key] ?: return
        if (draft.text.isBlank() && draft.files.isEmpty()) return
        drafts.markSendStarted(key, draft.version)
        launch(key, SessionLane.Send) {
            try {
                draft.files.forEachIndexed { index, file ->
                    mutableSendProgress.update {
                        it + (key to SendProgress.Uploading(index + 1, draft.files.size, file.name))
                    }
                    actions.upload(session, file)
                }
                currentCoroutineContext().ensureActive()
                mutableSendProgress.update { it + (key to SendProgress.Submitting) }
                try {
                    actions.prompt(session, draft.text, draft.files.map { it.id }, draft.intentId)
                } catch (failure: TerminalCommandFailure) {
                    drafts.rotateFailedIntent(key, draft.version, draft.intentId)
                    throw failure
                }
                clearSentDraft(key, draft.version)
            } finally {
                mutableSendProgress.update { it - key }
            }
        }
    }

    fun command(session: Session, command: SessionUserCommand) {
        val key = SessionKey(session.hostId, session.id)
        if (command.lane == SessionLane.Control) cancelSend(key)
        launch(key, command.lane) {
            if (command == SessionUserCommand.Start) synchronized(lock) {
                val baseline = details[key]?.operations?.lastOrNull { it.commandType == "start_runtime" }?.commandId
                mutableRuntimeStarts.update { it + (key to RuntimeStartAttempt.Pending(baseline)) }
            }
            // Gateway orders accepted prompts before Interrupt and fences Stop by runtime generation.
            // Cancellation cannot prove an in-flight POST was not delivered; its durable outbox
            // row remains for receipt reconciliation before the next prompt.
            // Waiting for the local Send job could strand Stop behind a blocked content provider.
            try {
                actions.command(session, command)
            } catch (failure: Exception) {
                if (command == SessionUserCommand.Start) synchronized(lock) {
                    mutableRuntimeStarts.update { attempts ->
                        if (attempts[key] is RuntimeStartAttempt.Pending)
                            attempts + (key to RuntimeStartAttempt.Failed(failure.message ?: "Could not start OMP"))
                        else attempts
                    }
                }
                throw failure
            }
        }
    }

    fun respond(session: Session, response: AttentionResponse) = launch(session.key(), SessionLane.Action) {
        actions.respond(session, response)
    }

    /** One Action job keeps both commands ordered and prevents lane de-duplication dropping either. */
    fun applyModelSettings(session: Session, changes: ModelSettingsChanges): Boolean {
        if (changes.isEmpty) return false
        return launch(session.key(), SessionLane.Action) {
            changes.model?.let { actions.selectModel(session, it) }
            changes.thinkingLevel?.let { actions.setThinkingLevel(session, it) }
            changes.fastModeEnabled?.let { actions.setFastMode(session, it) }
        }
    }

    fun loadEarlierHistory(session: Session) = launch(session.key(), SessionLane.History) {
        actions.loadEarlierHistory(session)
    }

    fun cancelHost(hostId: String) {
        val active = synchronized(lock) { jobs.filterKeys { it.session.hostId == hostId }.values.toList() }
        active.forEach(Job::cancel)
        synchronized(lock) {
            mutableRuntimeStarts.update { it.filterKeys { key -> key.hostId != hostId } }
        }
    }

    private fun cancelSend(key: SessionKey) {
        synchronized(lock) { jobs[SessionOperationKey(key, SessionLane.Send)] }?.cancel()
    }

    private fun launch(key: SessionKey, lane: SessionLane, action: suspend () -> Unit): Boolean {
        val operation = SessionOperationKey(key, lane)
        val job = synchronized(lock) {
            if (operation in jobs) return false
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    action()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    reportError(failure.message ?: "Action failed")
                }
            }.also { newJob ->
                jobs[operation] = newJob
                mutableOperations.value += operation
                newJob.invokeOnCompletion {
                    synchronized(lock) {
                        if (jobs[operation] === newJob) {
                            jobs.remove(operation)
                            mutableOperations.value -= operation
                        }
                    }
                }
            }
        }
        job.start()
        return true
    }
}

private fun Session.key() = SessionKey(hostId, id)
