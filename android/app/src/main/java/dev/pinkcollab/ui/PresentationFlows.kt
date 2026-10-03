package dev.pinkcollab.ui

import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.ConnectionProgress
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.InitialSyncState
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.compareTimestamps
import dev.pinkcollab.data.TaskListLoadState
import dev.pinkcollab.data.Workspace
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform

/** Only cumulative presentation snapshots may be conflated; protocol events must stay ordered. */
internal fun <T> Flow<T>.batchLatestPresentation(intervalMillis: Long = 75): Flow<T> {
    require(intervalMillis > 0)
    return buffer(Channel.CONFLATED).transform { snapshot ->
        emit(snapshot)
        delay(intervalMillis)
    }
}

internal fun compareSessionsForPresentation(a: Session, b: Session): Int = when {
    a.isActive != b.isActive -> if (a.isActive) -1 else 1
    a.isActive -> compareTimestamps(b.createdAt, a.createdAt)
    else -> compareTimestamps(b.updatedAt, a.updatedAt)
}

internal fun sessionForFocus(app: SessionListState, selected: SessionKey?): Session? {
    if (app.loadState == TaskListLoadState.Loading) return null
    val sessions = app.hosts.values.asSequence().flatMap { it.sessions.asSequence() }
    if (selected != null) {
        app.hosts[selected.hostId]?.sessions?.firstOrNull { it.id == selected.sessionId }?.let { return it }
    }
    return sessions.minWithOrNull(::compareSessionsForPresentation)
}

/** The Tasks route owns this collector, including its startup wait and pending debounce. */
internal suspend fun Flow<SessionListState>.followTaskFocus(
    selections: Flow<SessionKey?>,
    focus: (Session) -> Unit,
) {
    var lastRequested: Pair<SessionKey, String?>? = null
    combine(selections) { app, selected ->
        sessionForFocus(app, selected)?.let { session ->
            session to (SessionKey(session.hostId, session.id) to app.hosts[session.hostId]?.snapshotToken)
        }
    }.distinctUntilChangedBy { it?.second }.collectLatest { target ->
        if (target == null || target.second == lastRequested) return@collectLatest
        if (lastRequested != null) delay(200)
        focus(target.first)
        lastRequested = target.second
    }
}

internal data class NavigationHost(
    val host: Host,
    val url: String,
    val connection: ConnectionState,
    val initialSync: InitialSyncState,
    val workspaces: List<Workspace>,
    val activeTasks: Int,
    val connectionProgress: ConnectionProgress? = null,
) {
    val connected: Boolean get() = connection is ConnectionState.Online
}

internal data class NavigationState(
    val hosts: Map<String, NavigationHost>,
    val loadingCredentials: Boolean,
)

/** Navigation has no transcripts, credentials, or socket revision counters. */
internal fun navigationState(app: AppState) = NavigationState(
    hosts = app.hosts.mapValues { (_, host) ->
        NavigationHost(host.paired.host, host.paired.url, host.connection, host.initialSync,
            host.workspaces, host.sessions.count { it.isActive }, host.connectionProgress)
    },
    loadingCredentials = app.loadingCredentials,
)

internal enum class TaskListEmptyState { Loading, Ready, Recovery }

internal data class SessionListState(
    val hosts: Map<String, HostState>,
    val loadState: TaskListLoadState,
    val emptyState: TaskListEmptyState = when (loadState) {
        TaskListLoadState.Loading -> TaskListEmptyState.Loading
        TaskListLoadState.Ready -> TaskListEmptyState.Ready
        TaskListLoadState.Unavailable -> TaskListEmptyState.Recovery
    },
) {
    // Tasks use these host fields; protocol revisions and workspace changes do not affect them.
    fun samePresentation(other: SessionListState): Boolean =
        loadState == other.loadState && emptyState == other.emptyState && hosts.keys == other.hosts.keys && hosts.all { (id, host) ->
            val next = other.hosts.getValue(id)
            host.sessions == next.sessions && host.connection == next.connection &&
                host.totalSessions == next.totalSessions && host.nextSessionsCursor == next.nextSessionsCursor &&
                host.snapshotToken == next.snapshotToken && host.initialSync == next.initialSync &&
                (host.connectionProgress != null) == (next.connectionProgress != null)
        }
}

internal fun sessionListState(app: AppState) = SessionListState(app.hosts, app.taskListLoadState)

/** Keep summaries current while Tasks is off screen; transcript changes are observed separately. */
internal fun StateFlow<AppState>.sessionListPresentation(scope: CoroutineScope): StateFlow<SessionListState> =
    map(::sessionListState)
        // The ViewModel owns recovery continuity, including while Tasks is off screen.
        .runningFold(sessionListState(value)) { previous, next ->
            if (next.loadState == TaskListLoadState.Loading && previous.emptyState == TaskListEmptyState.Recovery &&
                next.hosts.keys.any { it in previous.hosts }) next.copy(emptyState = TaskListEmptyState.Recovery)
            else next
        }
        .distinctUntilChanged(SessionListState::samePresentation)
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, sessionListState(value))
