package dev.pinkcollab.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes durable pairing changes and applies them to the latest runtime state. */
internal class PairedHostRegistry(
    private val store: PairedHostStore,
    private val state: MutableStateFlow<AppState>,
    private val connect: (PairedHost) -> Unit,
    private val disconnect: (String) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun initialize() = mutex.withLock {
        val saved = store.read()
        state.update { it.copy(hosts = saved.associate { paired -> paired.host.id to HostState(paired) }, loadingCredentials = false) }
        saved.forEach(connect)
    }

    suspend fun pair(paired: PairedHost) = mutex.withLock {
        // Once the durable write begins, complete the matching in-memory transition too.
        withContext(NonCancellable) {
            store.save(state.value.hosts.values.map { it.paired }.filterNot { it.host.id == paired.host.id } + paired)
            state.update { it.copy(hosts = it.hosts + (paired.host.id to HostState(paired))) }
            connect(paired)
        }
    }

    suspend fun forget(id: String) = mutex.withLock {
        withContext(NonCancellable) {
            store.save(state.value.hosts.values.map { it.paired }.filterNot { it.host.id == id })
            state.update { it.copy(hosts = it.hosts - id, details = it.details.filterValues { detail -> detail.session.hostId != id }) }
            disconnect(id)
        }
    }
}
