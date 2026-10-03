package dev.pinkcollab.ui

import dev.pinkcollab.data.*
import org.junit.Assert.*
import org.junit.Test

class SessionRecoveryTest {
    private fun host(connection: ConnectionState, sync: InitialSyncState = InitialSyncState.Unavailable, id: String = "host") =
        HostState(PairedHost(Host(id, "Desktop", "windows", "", ""), "https://host", "token", "client"),
            connection = connection, initialSync = sync)

    private val retried = mutableListOf<String>()
    private var paired = 0
    private var updated = 0
    private val actions = TasksScreenActions({}, {}, { paired++ }, { updated++ }, {},
        { _, _ -> }, { _, _ -> true }, { retried.add(it) })

    @Test fun retry_executes_only_failed_hosts_and_leaves_pending_and_ready_hosts_alone() {
        val button = recoveryPrimaryAction(listOf(
            host(ConnectionState.Offline(), id = "offline"),
            host(ConnectionState.Online(1), id = "failed-sync"),
            host(ConnectionState.Online(1), InitialSyncState.Ready, "ready"),
            host(ConnectionState.Connecting, id = "connecting"),
            host(ConnectionState.Synchronizing, id = "syncing"),
            host(ConnectionState.AuthenticationRequired, id = "auth"),
            host(ConnectionState.UpgradeRequired, id = "upgrade"),
        ), actions)
        assertEquals("Try again", button.label)
        assertTrue(button.enabled)
        button.onClick()
        assertEquals(listOf("offline", "failed-sync"), retried)
        assertEquals(0, paired)
        assertEquals(0, updated)
    }

    @Test fun pending_or_successful_empty_hosts_have_no_retry_action() {
        for (hosts in listOf(emptyList(), listOf(host(ConnectionState.Connecting)),
            listOf(host(ConnectionState.Synchronizing)),
            listOf(host(ConnectionState.Online(1), InitialSyncState.Ready)))) {
            val button = recoveryPrimaryAction(hosts, actions)
            assertFalse(button.enabled)
            button.onClick()
        }
        assertTrue(retried.isEmpty())
    }

    @Test fun terminal_failures_offer_pairing_then_updates_even_when_another_host_is_pending() {
        val pending = host(ConnectionState.Synchronizing)
        val pairing = recoveryPrimaryAction(listOf(pending, host(ConnectionState.AuthenticationRequired),
            host(ConnectionState.UpgradeRequired)), actions)
        assertEquals("Reconnect host", pairing.label)
        assertTrue(pairing.enabled)
        pairing.onClick()
        assertEquals(1, paired)
        assertEquals(0, updated)
        val update = recoveryPrimaryAction(listOf(pending, host(ConnectionState.UpgradeRequired)), actions)
        assertEquals("Update app", update.label)
        assertTrue(update.enabled)
        update.onClick()
        assertEquals(1, updated)
        assertTrue(retried.isEmpty())
    }

    @Test fun failures_use_plain_language_without_exposing_server_errors() {
        assertEquals("The host is temporarily unavailable.",
            recoveryHostDetail(host(ConnectionState.Offline("Host rejected the connection (HTTP 502)"))))
        assertEquals("Couldn’t establish a secure connection.",
            recoveryHostDetail(host(ConnectionState.Offline("Different diagnostic wording", ConnectionFailure.SecureConnectionFailed))))
        assertEquals("Check your phone’s internet connection.",
            recoveryHostDetail(host(ConnectionState.Offline(null, ConnectionFailure.NetworkUnavailable))))
        assertEquals("Couldn’t find this host.",
            recoveryHostDetail(host(ConnectionState.Offline(null, ConnectionFailure.HostNotFound))))
        assertEquals("The host didn’t respond in time.",
            recoveryHostDetail(host(ConnectionState.Offline(null, ConnectionFailure.TimedOut))))
    }

    @Test fun successful_empty_host_is_not_presented_as_a_load_failure() {
        assertEquals("No sessions yet.",
            recoveryHostDetail(host(ConnectionState.Online(1), InitialSyncState.Ready)))
        assertEquals("Couldn’t load sessions from this host.",
            recoveryHostDetail(host(ConnectionState.Online(1))))
    }
}
