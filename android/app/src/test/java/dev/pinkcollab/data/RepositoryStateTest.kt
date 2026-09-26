package dev.pinkcollab.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryStateTest {
    private fun paired(id: String) = PairedHost(Host(id, id, "linux", "", ""), "https://example.test", "secret", "client")

    @Test fun snapshot_timeout_is_a_business_error_but_caller_cancellation_is_not() = runTest {
        val state = MutableStateFlow(false)
        var failure: Throwable? = null
        val timedOut = launch {
            try {
                awaitSnapshot(state, "snapshot timeout") { it }
            } catch (error: Throwable) {
                failure = error
            }
        }
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        timedOut.join()
        assertTrue(failure is IOException)
        assertEquals("snapshot timeout", failure?.message)

        var cancelledFailure: Throwable? = null
        val cancelled = launch {
            try {
                awaitSnapshot(state, "snapshot timeout") { it }
            } catch (error: CancellationException) {
                cancelledFailure = error
                throw error
            }
        }
        runCurrent()
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        assertTrue(cancelledFailure is CancellationException)
    }

    @Test fun pairing_preserves_another_hosts_newer_socket_state_during_credential_save() = runTest {
        val original = paired("original")
        val added = paired("added")
        val state = MutableStateFlow(AppState(hosts = mapOf("original" to HostState(original, revision = 10))))
        val saveStarted = CompletableDeferred<Unit>()
        val finishSave = CompletableDeferred<Unit>()
        val store = object : PairedHostStore {
            override suspend fun read() = emptyList<PairedHost>()
            override suspend fun save(hosts: List<PairedHost>) {
                saveStarted.complete(Unit)
                finishSave.await()
            }
        }
        val connected = mutableListOf<String>()
        val registry = PairedHostRegistry(store, state, { connected += it.host.id }, {})
        val pairing = launch { registry.pair(added) }
        saveStarted.await()
        state.update { app -> app.copy(hosts = app.hosts + ("original" to app.hosts.getValue("original").copy(revision = 11))) }
        finishSave.complete(Unit)
        pairing.join()
        assertEquals(11, state.value.hosts.getValue("original").revision)
        assertTrue("added" in state.value.hosts)
        assertEquals(listOf("added"), connected)
    }

    @Test fun forgetting_preserves_another_hosts_newer_socket_state_during_credential_save() = runTest {
        val removed = paired("removed")
        val retained = paired("retained")
        val state = MutableStateFlow(AppState(hosts = mapOf(
            "removed" to HostState(removed),
            "retained" to HostState(retained, revision = 10),
        )))
        val saveStarted = CompletableDeferred<Unit>()
        val finishSave = CompletableDeferred<Unit>()
        val store = object : PairedHostStore {
            override suspend fun read() = emptyList<PairedHost>()
            override suspend fun save(hosts: List<PairedHost>) {
                saveStarted.complete(Unit)
                finishSave.await()
            }
        }
        val disconnected = mutableListOf<String>()
        val registry = PairedHostRegistry(store, state, {}, { disconnected += it })
        val forgetting = launch { registry.forget("removed") }
        saveStarted.await()
        state.update { app -> app.copy(hosts = app.hosts + ("retained" to app.hosts.getValue("retained").copy(revision = 11))) }
        finishSave.complete(Unit)
        forgetting.join()
        assertEquals(11, state.value.hosts.getValue("retained").revision)
        assertTrue("removed" !in state.value.hosts)
        assertEquals(listOf("removed"), disconnected)
    }

    @Test fun cancellation_after_durable_write_begins_still_finishes_the_state_transition() = runTest {
        val added = paired("added")
        val state = MutableStateFlow(AppState())
        val saveStarted = CompletableDeferred<Unit>()
        val finishSave = CompletableDeferred<Unit>()
        val store = object : PairedHostStore {
            override suspend fun read() = emptyList<PairedHost>()
            override suspend fun save(hosts: List<PairedHost>) {
                saveStarted.complete(Unit)
                finishSave.await()
            }
        }
        val connected = mutableListOf<String>()
        val registry = PairedHostRegistry(store, state, { connected += it.host.id }, {})
        val pairing = launch { registry.pair(added) }
        saveStarted.await()
        pairing.cancel()
        finishSave.complete(Unit)
        pairing.join()
        assertTrue("added" in state.value.hosts)
        assertEquals(listOf("added"), connected)
    }
}
