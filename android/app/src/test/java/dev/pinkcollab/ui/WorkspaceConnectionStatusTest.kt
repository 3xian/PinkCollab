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
        assertEquals("Connection established. Syncing sessions and directories",
            workspaceConnectionMessage(navigation(ConnectionState.Synchronizing)))
    }

    @Test fun offline_reports_the_failure_without_claiming_periodic_checks() {
        val waiting = navigation(ConnectionState.Offline("Connection timed out"), ConnectionProgress(2, "Connection timed out"))
        assertEquals("Connection timed out", workspaceConnectionMessage(waiting))
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
        val stopped = after.copy(hosts = after.hosts.mapValues { (_, host) -> host.copy(connectionProgress = null) })
        assertFalse(sessionListState(after).samePresentation(sessionListState(stopped)))
    }
}

