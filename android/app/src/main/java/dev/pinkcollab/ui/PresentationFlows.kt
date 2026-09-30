package dev.pinkcollab.ui

import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.InitialSyncState
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

internal data class NavigationHost(
    val host: Host,
    val url: String,
    val connection: ConnectionState,
    val initialSync: InitialSyncState,
    val workspaces: List<Workspace>,
    val activeTasks: Int,
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
            host.workspaces, host.sessions.count { it.isActive })
    },
    loadingCredentials = app.loadingCredentials,
)

internal data class SessionListState(
    val hosts: Map<String, HostState>,
    val loadState: TaskListLoadState,
) {
    // Tasks use these host fields; protocol revisions and workspace changes do not affect them.
    fun samePresentation(other: SessionListState): Boolean =
        loadState == other.loadState && hosts.keys == other.hosts.keys && hosts.all { (id, host) ->
            val next = other.hosts.getValue(id)
            host.sessions == next.sessions && host.connection == next.connection &&
                host.snapshotToken == next.snapshotToken
        }
}

internal fun sessionListState(app: AppState) = SessionListState(app.hosts, app.taskListLoadState)

/** Keep summaries current while Tasks is off screen; transcript changes are observed separately. */
internal fun StateFlow<AppState>.sessionListPresentation(scope: CoroutineScope): StateFlow<SessionListState> =
    map(::sessionListState)
        .distinctUntilChanged(SessionListState::samePresentation)
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, sessionListState(value))
