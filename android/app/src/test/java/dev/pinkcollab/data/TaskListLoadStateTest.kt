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
    fun `timed out reconnect remains unavailable while retries continue`() {
        assertEquals(
            TaskListLoadState.Unavailable,
            appWithHost(
                connection = ConnectionState.Reconnecting,
                initialSync = InitialSyncState.Unavailable,
            ).taskListLoadState,
        )
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
