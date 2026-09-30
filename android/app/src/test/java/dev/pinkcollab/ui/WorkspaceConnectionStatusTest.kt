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

    @Test fun reconnecting_reports_failure_and_current_attempt_then_clears_on_recovery() {
        val retrying = navigation(ConnectionState.Reconnecting, ConnectionProgress(3, "Connection timed out"))
        assertEquals("Connection timed out. Retrying automatically (attempt 3)", workspaceConnectionMessage(retrying))
        assertEquals("Retrying connection automatically", workspaceConnectionMessage(navigation(ConnectionState.Reconnecting)))
        assertEquals("Network unavailable", workspaceConnectionMessage(navigation(ConnectionState.Offline("Network unavailable"))))
        assertEquals("No allowed directories", workspaceConnectionMessage(navigation(ConnectionState.Online(1))))
    }

    @Test fun progress_updates_navigation_without_invalidating_session_presentation() {
        val before = AppState(hosts = mapOf("host" to HostState(paired,
            connection = ConnectionState.Reconnecting, connectionProgress = ConnectionProgress(2))))
        val after = before.copy(hosts = before.hosts.mapValues { (_, host) ->
            host.copy(connectionProgress = ConnectionProgress(3, "Cannot resolve host address"))
        })
        assertNotEquals(navigationState(before), navigationState(after))
        assertTrue(sessionListState(before).samePresentation(sessionListState(after)))
    }
}
