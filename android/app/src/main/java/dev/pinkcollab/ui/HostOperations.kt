package dev.pinkcollab.ui

import dev.pinkcollab.data.GatewayRepository
import dev.pinkcollab.data.Listing
import dev.pinkcollab.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class BrowserKey(val hostId: String, val path: String)
internal data class DirectoryLoad(val key: BrowserKey, val state: LoadState<Listing>)

internal sealed interface UiEffect {
    data class ShowSnackbar(val message: String) : UiEffect
    data class HostPaired(val attemptId: Long) : UiEffect
    data class PairingFailed(val attemptId: Long, val message: String) : UiEffect
    data class SessionCreated(val key: SessionKey) : UiEffect
}

internal interface HostActions {
    suspend fun pair(url: String, token: String)
    suspend fun refreshHost(hostId: String)
    fun reconnect(hostId: String)
    fun reconnectUnavailableHosts()
    suspend fun forget(hostId: String)
    suspend fun listing(hostId: String, path: String, forceRefresh: Boolean): Listing
    fun prefetchListings(hostId: String, paths: List<String>)
    suspend fun create(hostId: String, path: String): Session
}

internal class RepositoryHostActions(private val repository: GatewayRepository) : HostActions {
    override suspend fun pair(url: String, token: String) = repository.pair(url, token)
    override suspend fun refreshHost(hostId: String) = repository.refreshHost(hostId)
    override fun reconnect(hostId: String) = repository.requestReconnect(hostId)
    override fun reconnectUnavailableHosts() = repository.reconnectUnavailableHosts()
    override suspend fun forget(hostId: String) = repository.forget(hostId)
    override suspend fun listing(hostId: String, path: String, forceRefresh: Boolean) =
        repository.listing(hostId, path, forceRefresh)
    override fun prefetchListings(hostId: String, paths: List<String>) = repository.prefetchListings(hostId, paths)
    override suspend fun create(hostId: String, path: String) = repository.create(hostId, path)
}

/** Owns host and browser requests; navigation responds to effects after a successful operation. */
internal class HostOperations(
    private val scope: CoroutineScope,
    private val actions: HostActions,
    private val emit: (UiEffect) -> Unit,
    private val onHostForgetting: (String) -> Unit,
    private val onHostForgotten: (String) -> Unit,
    private val onSessionCreated: (Session) -> Unit,
) {
    private val lock = Any()
    private val mutableOperations = MutableStateFlow<Set<OperationKey>>(emptySet())
    val operations = mutableOperations.asStateFlow()
    private val mutableDirectory = MutableStateFlow<DirectoryLoad?>(null)
    val directory = mutableDirectory.asStateFlow()
    private var directoryRequest: Long? = null
    private var nextDirectoryRequest = 0L

    fun pair(url: String, token: String, attemptId: Long) = run(OperationKey.PairHost,
        onError = { emit(UiEffect.PairingFailed(attemptId, it)) }) {
        actions.pair(url, token)
        emit(UiEffect.HostPaired(attemptId))
    }

    fun refresh(hostId: String, isOnline: Boolean) {
        if (isOnline) run(OperationKey.Host(hostId)) { actions.refreshHost(hostId) }
        else actions.reconnect(hostId)
    }

    fun reconnectUnavailableHosts() = actions.reconnectUnavailableHosts()

    fun forget(hostId: String) = run(OperationKey.Host(hostId)) {
        onHostForgetting(hostId)
        actions.forget(hostId)
        onHostForgotten(hostId)
        synchronized(lock) {
            if (mutableDirectory.value?.key?.hostId == hostId) {
                directoryRequest = null
                mutableDirectory.value = null
            }
        }
    }

    fun loadDirectory(key: BrowserKey, forceRefresh: Boolean = false) {
        val request = synchronized(lock) {
            if (!forceRefresh && mutableDirectory.value == DirectoryLoad(key, LoadState.Loading)) return
            (++nextDirectoryRequest).also {
                directoryRequest = it
                mutableDirectory.value = DirectoryLoad(key, LoadState.Loading)
            }
        }
        scope.launch {
            try {
                val listing = actions.listing(key.hostId, key.path, forceRefresh)
                val current = synchronized(lock) {
                    if (directoryRequest != request) false else {
                        directoryRequest = null
                        mutableDirectory.value = DirectoryLoad(key, LoadState.Ready(listing))
                        true
                    }
                }
                if (current) actions.prefetchListings(key.hostId, listing.directories.map { it.path })
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                synchronized(lock) {
                    if (directoryRequest == request) {
                        directoryRequest = null
                        mutableDirectory.value = DirectoryLoad(key,
                            LoadState.Failed(failure.message ?: "Unable to read this directory"))
                    }
                }
            }
        }
    }

    fun create(hostId: String, path: String) = run(OperationKey.CreateTask(hostId, path), "Unable to create session") {
        val session = actions.create(hostId, path)
        onSessionCreated(session)
        emit(UiEffect.SessionCreated(SessionKey(session.hostId, session.id)))
        run(OperationKey.Host(hostId)) { actions.refreshHost(hostId) }
    }

    private fun run(
        key: OperationKey,
        fallbackError: String = "Action failed",
        onError: (String) -> Unit = { emit(UiEffect.ShowSnackbar(it)) },
        action: suspend () -> Unit,
    ) {
        synchronized(lock) {
            if (key in mutableOperations.value) return
            mutableOperations.value += key
        }
        scope.launch {
            try {
                action()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                onError(failure.message ?: fallbackError)
            } finally {
                synchronized(lock) { mutableOperations.value -= key }
            }
        }
    }
}
