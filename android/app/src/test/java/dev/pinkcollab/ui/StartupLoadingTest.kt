package dev.pinkcollab.ui

import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.InitialSyncState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionStatus
import dev.pinkcollab.data.TaskListLoadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupLoadingTest {
    private val paired = PairedHost(
        host = Host("host-1", "Desktop", "Windows", "", ""),
        url = "https://gateway.example",
        credential = "credential",
        clientId = "client-1",
    )

    @Test
    fun `ready state releases startup immediately`() = runTest {
        var ready = false
        launch { awaitStartupReadiness(MutableStateFlow(AppState())); ready = true }
        runCurrent()
        assertTrue(ready)
    }

    @Test
    fun `startup waits for the first task snapshot`() = runTest {
        val states = MutableStateFlow(appWithHost(ConnectionState.Synchronizing))
        var ready = false
        launch {
            awaitStartupReadiness(states, maximumDurationMillis = 8_000)
            ready = true
        }

        runCurrent()
        assertFalse(ready)

        states.value = appWithHost(
            connection = ConnectionState.Online(1),
            lastSyncedAtEpochMillis = 1,
            initialSync = InitialSyncState.Ready,
        )
        runCurrent()
        assertTrue(ready)
    }

    @Test
    fun `snapshot arriving after three seconds releases startup without extra delay`() = runTest {
        val states = MutableStateFlow(appWithHost(ConnectionState.Synchronizing))
        var ready = false
        launch {
            awaitStartupReadiness(states, maximumDurationMillis = 8_000)
            ready = true
        }

        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(ready)

        states.value = appWithHost(
            connection = ConnectionState.Online(1),
            lastSyncedAtEpochMillis = 1,
            initialSync = InitialSyncState.Ready,
        )
        runCurrent()

        assertTrue(ready)
    }

    @Test
    fun `startup timeout releases the global loading screen`() = runTest {
        val states = MutableStateFlow(appWithHost(ConnectionState.Connecting))
        var ready = false
        launch {
            awaitStartupReadiness(states, maximumDurationMillis = 8_000)
            ready = true
        }

        advanceTimeBy(7_999)
        runCurrent()
        assertFalse(ready)
        advanceTimeBy(1)
        runCurrent()

        assertTrue(ready)
    }

    @Test
    fun `readiness stays complete when a host is paired later`() = runTest {
        val states = MutableStateFlow(AppState())
        var ready = false
        launch {
            awaitStartupReadiness(states, maximumDurationMillis = 8_000)
            ready = true
        }

        runCurrent()
        assertTrue(ready)

        states.value = appWithHost(ConnectionState.Connecting)
        runCurrent()

        assertTrue(ready)
    }

    @Test
    fun `focus target preserves a restored selection and falls back when it was removed`() {
        val older = session("older", "2026-01-01T00:00:00Z")
        val newer = session("newer", "2026-01-02T00:00:00Z")
        val app = AppState(hosts = mapOf(paired.host.id to HostState(paired, sessions = listOf(older, newer))))
        assertEquals(older, sessionForFocus(sessionListState(app), SessionKey(paired.host.id, "older")))
        assertEquals(newer, sessionForFocus(sessionListState(app), SessionKey(paired.host.id, "removed")))
        assertNull(sessionForFocus(sessionListState(app.copy(loadingCredentials = true)), null))
    }

    @Test
    fun `focus target matches active and inactive ordering across hosts`() {
        val recentHistory = session("history", "2026-01-03T00:00:00Z")
        val active = session("active", "2026-01-01T00:00:00Z").copy(runtimeAttached = true)
        val otherPaired = paired.copy(host = paired.host.copy(id = "other"))
        val newerActive = active.copy(id = "other-active", hostId = "other", createdAt = "2026-01-02T00:00:00Z")
        val app = AppState(hosts = mapOf(
            paired.host.id to HostState(paired, sessions = listOf(recentHistory, active)),
            "other" to HostState(otherPaired, sessions = listOf(newerActive)),
        ))
        assertEquals(newerActive, sessionForFocus(sessionListState(app), null))
        assertEquals(recentHistory, sessionForFocus(sessionListState(app.copy(hosts = mapOf(
            paired.host.id to HostState(paired, sessions = listOf(recentHistory, active.copy(runtimeAttached = false))),
        ))), null))
    }

    @Test
    fun `leaving Tasks after startup timeout withdraws the pending focus`() = runTest {
        val states = MutableStateFlow(appWithHost(ConnectionState.Synchronizing))
        val selected = MutableStateFlow<SessionKey?>(null)
        val focused = mutableListOf<Session>()
        val focusJob = launch {
            states.map(::sessionListState).followTaskFocus(selected, focused::add)
        }
        var ready = false
        launch { awaitStartupReadiness(states); ready = true }
        advanceTimeBy(8_000)
        runCurrent()
        assertTrue(ready)
        assertTrue(focused.isEmpty())

        focusJob.cancel()
        states.value = states.value.copy(hosts = mapOf(
            paired.host.id to HostState(paired, sessions = listOf(session("late", ""), session("current", ""))),
        ))
        runCurrent()
        assertTrue(focused.isEmpty())

        selected.value = SessionKey(paired.host.id, "current")
        val resumed = launch {
            states.map(::sessionListState).followTaskFocus(selected, focused::add)
        }
        runCurrent()
        assertEquals(listOf("current"), focused.map { it.id })
        resumed.cancel()
    }

    @Test
    fun `focus tracks current selection and reconnect without duplicate summary refreshes`() = runTest {
        val first = session("first", "")
        val second = session("second", "")
        val host = HostState(paired, sessions = listOf(first, second), snapshotToken = "initial")
        val states = MutableStateFlow(SessionListState(mapOf(paired.host.id to host), TaskListLoadState.Ready))
        val selected = MutableStateFlow<SessionKey?>(SessionKey(paired.host.id, first.id))
        val focused = mutableListOf<String>()
        val job = launch { states.followTaskFocus(selected) { focused += it.id } }
        runCurrent()
        assertEquals(listOf("first"), focused)

        selected.value = SessionKey(paired.host.id, second.id)
        runCurrent()
        advanceTimeBy(199)
        runCurrent()
        assertEquals(listOf("first"), focused)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("first", "second"), focused)

        states.value = states.value.copy(hosts = mapOf(paired.host.id to host.copy(
            sessions = listOf(first, second.copy(title = "Updated")),
        )))
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("first", "second"), focused)
        states.value = states.value.copy(hosts = mapOf(paired.host.id to host.copy(snapshotToken = "reconnected")))
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("first", "second", "second"), focused)

        selected.value = SessionKey(paired.host.id, first.id)
        runCurrent()
        advanceTimeBy(100)
        job.cancel()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("first", "second", "second"), focused)
    }

    private fun session(id: String, timestamp: String) = Session(
        id, paired.host.id, "/work", id, SessionStatus.Idle, "History on host",
        false, null, timestamp, timestamp, false,
    )

    private fun appWithHost(
        connection: ConnectionState,
        lastSyncedAtEpochMillis: Long? = null,
        initialSync: InitialSyncState = InitialSyncState.Pending,
    ) = AppState(
        hosts = mapOf(
            paired.host.id to HostState(
                paired = paired,
                connection = connection,
                lastSyncedAtEpochMillis = lastSyncedAtEpochMillis,
                initialSync = initialSync,
            ),
        ),
    )
}
