package dev.pinkcollab.ui

import android.app.Application
import android.net.Uri
import dev.pinkcollab.data.GatewayRepository
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.TerminalCommandFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal interface SessionActions {
    suspend fun upload(session: Session, file: SelectedFile)
    suspend fun command(session: Session, command: String, body: JSONObject = JSONObject(), intentId: String? = null)
    suspend fun selectModel(session: Session, model: ModelInfo)
    suspend fun setThinkingLevel(session: Session, level: String)
    suspend fun loadSavedHistory(session: Session)
    suspend fun loadEarlierHistory(session: Session)
}

internal class RepositorySessionActions(
    private val application: Application,
    private val repository: GatewayRepository,
) : SessionActions {
    override suspend fun upload(session: Session, file: SelectedFile) =
        repository.uploadFile(application, session.hostId, session.id, file.id, file.name, Uri.parse(file.uri))

    override suspend fun command(session: Session, command: String, body: JSONObject, intentId: String?) =
        repository.command(session.hostId, session.id, command, body, intentId)

    override suspend fun selectModel(session: Session, model: ModelInfo) =
        repository.selectModel(session.hostId, session.id, model)

    override suspend fun setThinkingLevel(session: Session, level: String) =
        repository.setThinkingLevel(session.hostId, session.id, level)

    override suspend fun loadSavedHistory(session: Session) = repository.loadSavedHistory(session.hostId, session.id)
    override suspend fun loadEarlierHistory(session: Session) = repository.loadEarlierHistory(session.hostId, session.id)
}

internal enum class SessionLane { Send, Action, History, Control }

internal enum class SessionUserCommand(val wire: String, val lane: SessionLane) {
    Start("start", SessionLane.Action),
    Interrupt("interrupt", SessionLane.Control),
    Stop("stop", SessionLane.Control),
}

internal data class SessionOperationKey(val session: SessionKey, val lane: SessionLane)

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
    private val reportError: (String?) -> Unit,
) {
    private val lock = Any()
    private val jobs = mutableMapOf<SessionOperationKey, Job>()
    private val mutableOperations = MutableStateFlow<Set<SessionOperationKey>>(emptySet())
    val operations = mutableOperations.asStateFlow()

    fun send(session: Session) {
        val key = SessionKey(session.hostId, session.id)
        val draft = drafts.state.value[key] ?: return
        if (draft.text.isBlank() && draft.files.isEmpty()) return
        drafts.markSendStarted(key, draft.version)
        launch(key, SessionLane.Send) {
            draft.files.forEach { actions.upload(session, it) }
            currentCoroutineContext().ensureActive()
            try {
                actions.command(session, "prompt", JSONObject().put("message", draft.text)
                    .put("fileIds", JSONArray(draft.files.map { it.id })), draft.intentId)
            } catch (failure: TerminalCommandFailure) {
                drafts.rotateFailedIntent(key, draft.version, draft.intentId)
                throw failure
            }
            clearSentDraft(key, draft.version)
        }
    }

    fun command(session: Session, command: SessionUserCommand) {
        val key = SessionKey(session.hostId, session.id)
        if (command.lane == SessionLane.Control) cancelSend(key)
        launch(key, command.lane) {
            actions.command(session, command.wire)
        }
    }

    fun respond(session: Session, body: JSONObject) = launch(session.key(), SessionLane.Action) {
        actions.command(session, "respond", body)
    }

    fun selectModel(session: Session, model: ModelInfo) = launch(session.key(), SessionLane.Action) {
        actions.selectModel(session, model)
    }

    fun setThinkingLevel(session: Session, level: String) = launch(session.key(), SessionLane.Action) {
        actions.setThinkingLevel(session, level)
    }

    fun loadSavedHistory(session: Session) = launch(session.key(), SessionLane.History) {
        actions.loadSavedHistory(session)
    }

    fun loadEarlierHistory(session: Session) = launch(session.key(), SessionLane.History) {
        actions.loadEarlierHistory(session)
    }

    private fun cancelSend(key: SessionKey) {
        synchronized(lock) { jobs[SessionOperationKey(key, SessionLane.Send)] }?.cancel()
    }

    private fun launch(key: SessionKey, lane: SessionLane, action: suspend () -> Unit) {
        val operation = SessionOperationKey(key, lane)
        val job = synchronized(lock) {
            if (operation in jobs) return
            scope.launch(start = CoroutineStart.LAZY) {
                reportError(null)
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
    }
}

private fun Session.key() = SessionKey(hostId, id)
