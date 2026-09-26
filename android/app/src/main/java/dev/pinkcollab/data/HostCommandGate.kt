package dev.pinkcollab.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lets commands run concurrently while host removal waits for them and rejects new work. */
internal class HostCommandGate {
    private class HostState {
        val removalMutex = Mutex()
        var activeCommands = 0
        var removing = false
        var drained: CompletableDeferred<Unit>? = null
    }

    private val lock = Any()
    private val hosts = mutableMapOf<String, HostState>()
    private fun state(hostId: String) = synchronized(lock) { hosts.getOrPut(hostId, ::HostState) }

    suspend fun <T> withHost(hostId: String, action: suspend () -> T): T {
        val host = state(hostId)
        synchronized(lock) {
            check(!host.removing) { "Host removal in progress" }
            host.activeCommands++
        }
        try {
            return action()
        } finally {
            synchronized(lock) {
                host.activeCommands--
                if (host.activeCommands == 0) host.drained?.complete(Unit)
            }
        }
    }

    suspend fun <T> withHostRemoval(hostId: String, action: suspend () -> T): T {
        val host = state(hostId)
        return host.removalMutex.withLock {
            val drained = synchronized(lock) {
                host.removing = true
                if (host.activeCommands == 0) null else CompletableDeferred<Unit>().also { host.drained = it }
            }
            try {
                drained?.await()
                action()
            } finally {
                synchronized(lock) {
                    host.removing = false
                    host.drained = null
                }
            }
        }
    }
}
