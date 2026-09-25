package dev.pinkcollab.ui

import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.InitialSyncState
import dev.pinkcollab.data.PairedHost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
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
    fun `ready state keeps startup visible for three seconds`() = runTest {
        val states = MutableStateFlow(AppState())
        var ready = false
        launch {
            awaitStartupReadiness(states, maximumDurationMillis = 8_000)
            ready = true
        }

        runCurrent()
        assertFalse(ready)
        advanceTimeBy(2_999)
        runCurrent()
        assertFalse(ready)
        advanceTimeBy(1)
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
        assertFalse(ready)
        advanceTimeBy(3_000)
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
        val states = MutableStateFlow(appWithHost(ConnectionState.Reconnecting))
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
        assertFalse(ready)
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(ready)

        states.value = appWithHost(ConnectionState.Connecting)
        runCurrent()

        assertTrue(ready)
    }

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
