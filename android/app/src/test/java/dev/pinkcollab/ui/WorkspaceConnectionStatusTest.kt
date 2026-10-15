package dev.pinkcollab.ui

import dev.pinkcollab.data.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceConnectionStatusTest {
    private val paired = PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client")

    private fun navigation(connection: ConnectionState): NavigationHost =
        navigationState(AppState(hosts = mapOf("host" to HostState(paired, connection = connection)))).hosts.getValue("host")

    @Test fun connecting_and_syncing_show_the_actual_stage() {
        assertEquals("Opening connection to host", workspaceConnectionMessage(navigation(ConnectionState.Connecting)))
        assertEquals("Connection established. Syncing sessions and directories",
            workspaceConnectionMessage(navigation(ConnectionState.Synchronizing)))
    }

    @Test fun offline_reports_the_failure_without_claiming_periodic_checks() {
        val waiting = navigation(ConnectionState.Offline("Connection timed out"))
        assertEquals("Connection timed out", workspaceConnectionMessage(waiting))
        assertEquals("Network unavailable", workspaceConnectionMessage(navigation(ConnectionState.Offline("Network unavailable"))))
        assertEquals("No allowed directories", workspaceConnectionMessage(navigation(ConnectionState.Online(1))))
    }

}

