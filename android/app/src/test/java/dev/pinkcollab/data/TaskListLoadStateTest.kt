package dev.pinkcollab.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskListLoadStateTest {
    private val paired = PairedHost(
        host = Host("host-1", "Host", "Windows", "1", "1"),
        url = "https://example.test",
        credential = "credential",
        clientId = "client-1",
    )

    @Test
    fun `no paired hosts is ready onboarding state`() {
        assertEquals(TaskListLoadState.Ready, AppState().taskListLoadState)
    }

    @Test
    fun `credential loading does not briefly show onboarding`() {
        assertEquals(TaskListLoadState.Loading, AppState(loadingCredentials = true).taskListLoadState)
    }

    @Test
    fun `host without snapshot is loading`() {
        assertEquals(
            TaskListLoadState.Loading,
            appWithHost(ConnectionState.Synchronizing).taskListLoadState,
        )
    }

    @Test
    fun `empty snapshot is a ready empty state`() {
        assertEquals(
            TaskListLoadState.Ready,
            appWithHost(
                connection = ConnectionState.Online(1),
                lastSyncedAtEpochMillis = 1,
                initialSync = InitialSyncState.Ready,
            ).taskListLoadState,
        )
    }

    @Test
    fun `unreachable host without snapshot is unavailable`() {
        assertEquals(
            TaskListLoadState.Unavailable,
            appWithHost(
                connection = ConnectionState.Offline("Network unavailable"),
                initialSync = InitialSyncState.Unavailable,
            ).taskListLoadState,
        )
    }

    @Test
    fun `network recovery clears initial failure until snapshot arrives`() {
        var host = HostState(paired)
        host = host.withConnectionState(ConnectionState.Offline("Network unavailable"), null)
        assertEquals(TaskListLoadState.Unavailable, AppState(hosts = mapOf(paired.host.id to host)).taskListLoadState)
        for (connection in listOf(ConnectionState.Connecting, ConnectionState.Reconnecting, ConnectionState.Synchronizing)) {
            host = host.withConnectionState(connection, ConnectionProgress(2))
            assertEquals(InitialSyncState.Pending, host.initialSync)
            assertEquals(TaskListLoadState.Loading, AppState(hosts = mapOf(paired.host.id to host)).taskListLoadState)
        }
    }

    @Test
    fun `terminal connection failures remain unavailable before first snapshot`() {
        for (connection in listOf(ConnectionState.Offline("Unreachable"),
            ConnectionState.AuthenticationRequired, ConnectionState.UpgradeRequired)) {
            val host = HostState(paired).withConnectionState(connection, null)
            assertEquals(TaskListLoadState.Unavailable, AppState(hosts = mapOf(paired.host.id to host)).taskListLoadState)
        }
    }

    @Test
    fun `connection loss preserves a known empty snapshot`() {
        val host = HostState(paired, initialSync = InitialSyncState.Ready)
            .withConnectionState(ConnectionState.Offline("Network unavailable"), null)
        assertEquals(TaskListLoadState.Ready, AppState(hosts = mapOf(paired.host.id to host)).taskListLoadState)
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
