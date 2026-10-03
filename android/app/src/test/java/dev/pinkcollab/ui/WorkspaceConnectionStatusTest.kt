package dev.pinkcollab.ui

import dev.pinkcollab.data.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceConnectionStatusTest {
    private val paired = PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client")

    private fun navigation(connection: ConnectionState, progress: ConnectionProgress? = null): NavigationHost =
        navigationState(AppState(hosts = mapOf("host" to HostState(paired,
            connection = connection, connectionProgress = progress)))).hosts.getValue("host")

    @Test fun connecting_and_syncing_show_the_actual_stage() {
        assertEquals("Opening connection to host", workspaceConnectionMessage(navigation(ConnectionState.Connecting)))
        assertEquals("Connection established; syncing sessions and directories",
            workspaceConnectionMessage(navigation(ConnectionState.Synchronizing)))
    }

    @Test fun offline_retry_reports_failure_then_clears_on_recovery() {
        val retrying = navigation(ConnectionState.Offline("Connection timed out"), ConnectionProgress(3, "Connection timed out"))
        assertEquals("Connection timed out. Checking periodically; refresh to retry now", workspaceConnectionMessage(retrying))
        assertEquals("Network unavailable", workspaceConnectionMessage(navigation(ConnectionState.Offline("Network unavailable"))))
        assertEquals("No allowed directories", workspaceConnectionMessage(navigation(ConnectionState.Online(1))))
    }

    @Test fun progress_updates_navigation_without_invalidating_session_presentation() {
        val before = AppState(hosts = mapOf("host" to HostState(paired,
            connection = ConnectionState.Offline(), connectionProgress = ConnectionProgress(2))))
        val after = before.copy(hosts = before.hosts.mapValues { (_, host) ->
            host.copy(connectionProgress = ConnectionProgress(3, "Cannot resolve host address"))
        })
        assertNotEquals(navigationState(before), navigationState(after))
        assertTrue(sessionListState(before).samePresentation(sessionListState(after)))
    }

    @Test fun offline_host_reports_periodic_checks_without_an_attempt_counter() {
        val offline = navigation(ConnectionState.Offline("Host rejected the connection (HTTP 502)"),
            ConnectionProgress(17, "Host rejected the connection (HTTP 502)"))
        assertEquals("Host rejected the connection (HTTP 502). Checking periodically; refresh to retry now",
            workspaceConnectionMessage(offline))
        assertEquals("Network unavailable",
            workspaceConnectionMessage(navigation(ConnectionState.Offline("Network unavailable"))))
    }
}
