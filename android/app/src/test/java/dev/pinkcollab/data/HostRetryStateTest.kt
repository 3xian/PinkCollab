package dev.pinkcollab.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HostRetryStateTest {
    @Test fun manual_foreground_failure_stays_offline_without_automatic_retries() = runTest {
        RecoveryHarness(backgroundScope).use { h ->
            h.connect()
            runCurrent()
            val failed = h.latest
            failed.fail(503)
            assertEquals(ConnectionState.Offline("Host rejected the connection (HTTP 503)",
                ConnectionFailure.HostRejected), h.state)
            repeat(3) {
                failed.fail(503)
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, h.client.attempts.size)
                assertTrue(h.state is ConnectionState.Offline)
            }
            assertEquals(InitialSyncState.Ready, h.host.initialSync)
        }
    }

    @Test fun background_failure_stays_quiet_and_foreground_restoration_recovers_immediately() = runTest {
        RecoveryHarness(backgroundScope).use { h ->
            h.connect(foreground = false)
            runCurrent()
            h.latest.synchronize()
            h.latest.fail()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, h.client.attempts.size)
            assertTrue(h.state is ConnectionState.Offline)

            h.supervisor.setForeground(true)
            runCurrent()
            assertEquals(2, h.client.attempts.size)
            assertEquals(ConnectionState.Connecting, h.state)
            h.supervisor.setForeground(true)
            runCurrent()
            assertEquals(2, h.client.attempts.size)
        }
    }

    @Test fun failed_foreground_recovery_exhausts_its_single_attempt_until_a_new_restoration() = runTest {
        RecoveryHarness(backgroundScope).use { h ->
            h.connect(foreground = false)
            runCurrent()
            h.latest.fail()
            h.supervisor.setForeground(true)
            runCurrent()
            assertEquals(2, h.client.attempts.size)
            val failedRecovery = h.latest
            failedRecovery.fail()
            repeat(3) {
                failedRecovery.fail()
                h.supervisor.setForeground(true)
                h.supervisor.setNetworkAvailable(true)
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(2, h.client.attempts.size)
                assertTrue(h.state is ConnectionState.Offline)
            }
            h.supervisor.setForeground(false)
            h.supervisor.setForeground(true)
            runCurrent()
            assertEquals(3, h.client.attempts.size)
        }
    }

    @Test fun foreground_restoration_during_a_manual_attempt_does_not_grant_a_second_attempt() = runTest {
        for (open in listOf(false, true)) {
            RecoveryHarness(backgroundScope).use { h ->
                h.connect(foreground = false)
                runCurrent()
                val pending = h.latest
                if (open) pending.open()
                h.supervisor.setForeground(true)
                if (open) pending.frame("host_sync") else pending.synchronize()
                pending.fail()
                pending.fail()
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, h.client.attempts.size)
                assertTrue(h.state is ConnectionState.Offline)
            }
        }
    }

    @Test fun another_restoration_during_automatic_connect_or_sync_cannot_extend_the_attempt() = runTest {
        for (open in listOf(false, true)) {
            RecoveryHarness(backgroundScope).use { h ->
                h.connect(foreground = false)
                runCurrent()
                h.latest.fail()
                h.supervisor.setForeground(true)
                runCurrent()
                val recovery = h.latest
                if (open) recovery.open()
                h.supervisor.setForeground(false)
                h.supervisor.setForeground(true)
                if (open) recovery.frame("host_sync") else recovery.synchronize()
                recovery.fail()
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(2, h.client.attempts.size)
                assertTrue(h.state is ConnectionState.Offline)
            }
        }
    }

    @Test fun foreground_while_network_is_missing_waits_for_network_restoration_in_either_order() = runTest {
        for (networkFirst in listOf(false, true)) {
            RecoveryHarness(backgroundScope).use { h ->
                h.connect(foreground = false)
                runCurrent()
                val old = h.latest
                old.synchronize()
                h.supervisor.setNetworkAvailable(false)
                if (networkFirst) h.supervisor.setNetworkAvailable(true) else h.supervisor.setForeground(true)
                old.fail()
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, h.client.attempts.size)
                if (networkFirst) h.supervisor.setForeground(true) else h.supervisor.setNetworkAvailable(true)
                runCurrent()
                assertEquals(2, h.client.attempts.size)
                val recovery = h.latest
                recovery.fail()
                h.supervisor.setForeground(true)
                h.supervisor.setNetworkAvailable(true)
                old.fail()
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(2, h.client.attempts.size)
                assertTrue(h.state is ConnectionState.Offline)
            }
        }
    }

    @Test fun background_clears_late_failure_opportunity_but_never_closes_a_healthy_socket() = runTest {
        RecoveryHarness(backgroundScope).use { h ->
            h.connect()
            runCurrent()
            val healthy = h.latest
            healthy.synchronize()
            h.supervisor.setForeground(false)
            advanceTimeBy(60_000)
            runCurrent()
            assertFalse(healthy.socket.cancelled)
            assertEquals(0, healthy.socket.closes)
            h.supervisor.setForeground(true)
            runCurrent()
            assertEquals(1, h.client.attempts.size)
            assertTrue(h.state is ConnectionState.Online)

            h.supervisor.setForeground(false)
            healthy.fail()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, h.client.attempts.size)
            assertTrue(h.state is ConnectionState.Offline)
            h.supervisor.setForeground(true)
            runCurrent()
            assertEquals(2, h.client.attempts.size)
        }
    }

    @Test fun failure_callback_after_thaw_consumes_the_healthy_socket_restoration_opportunity_once() = runTest {
        RecoveryHarness(backgroundScope).use { h ->
            h.connect()
            runCurrent()
            val frozen = h.latest
            frozen.synchronize()
            h.supervisor.setForeground(false)
            h.supervisor.setForeground(true)
            runCurrent()
            assertEquals(1, h.client.attempts.size)
            frozen.fail()
            runCurrent()
            assertEquals(2, h.client.attempts.size)
            val recovered = h.latest
            recovered.fail()
            frozen.fail()
            recovered.fail()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(2, h.client.attempts.size)
            assertTrue(h.state is ConnectionState.Offline)
        }
    }

    @Test fun network_loss_cancels_the_socket_and_restoration_recovers_only_in_foreground() = runTest {
        RecoveryHarness(backgroundScope).use { h ->
            h.connect()
            runCurrent()
            h.latest.synchronize()
            val old = h.latest
            h.supervisor.setNetworkAvailable(false)
            assertTrue(old.socket.cancelled)
            assertEquals(ConnectionState.Offline("Network unavailable", ConnectionFailure.NetworkUnavailable), h.state)
            old.fail()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, h.client.attempts.size)

            h.supervisor.setForeground(false)
            h.supervisor.setNetworkAvailable(true)
            runCurrent()
            assertEquals(1, h.client.attempts.size)
            h.supervisor.setForeground(true)
            runCurrent()
            assertEquals(2, h.client.attempts.size)
            h.latest.synchronize()

            h.supervisor.setNetworkAvailable(false)
            h.supervisor.setNetworkAvailable(true)
            runCurrent()
            assertEquals(3, h.client.attempts.size)
            h.latest.synchronize()
            h.supervisor.setNetworkAvailable(true)
            runCurrent()
            assertEquals(3, h.client.attempts.size)
            assertFalse(h.latest.socket.cancelled)
            h.latest.fail()
            h.latest.fail()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(3, h.client.attempts.size)
            assertTrue(h.state is ConnectionState.Offline)
        }
    }

    @Test fun connect_while_network_is_unavailable_waits_for_one_restoration_attempt() = runTest {
        RecoveryHarness(backgroundScope).use { h ->
            h.connect()
            runCurrent()
            h.latest.fail()
            runCurrent()
            h.supervisor.setNetworkAvailable(false)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, h.client.attempts.size)
            val explicit = h.supervisor.connect(h.paired)
            runCurrent()
            assertTrue(explicit.isCompleted)
            assertEquals(1, h.client.attempts.size)
            h.supervisor.setNetworkAvailable(true)
            runCurrent()
            assertEquals(2, h.client.attempts.size)
        }
    }

    @Test fun authentication_and_upgrade_require_explicit_connect_even_after_lifecycle_and_network_changes() = runTest {
        for ((code, state) in listOf(401 to ConnectionState.AuthenticationRequired, 426 to ConnectionState.UpgradeRequired)) {
            RecoveryHarness(backgroundScope).use { h ->
                h.connect()
                runCurrent()
                h.latest.fail(code)
                assertEquals(state, h.state)
                advanceTimeBy(60_000)
                runCurrent()
                h.supervisor.setForeground(false)
                h.supervisor.setNetworkAvailable(false)
                h.supervisor.setForeground(true)
                h.supervisor.setNetworkAvailable(true)
                runCurrent()
                assertEquals(state, h.state)
                assertEquals(1, h.client.attempts.size)
                h.supervisor.connect(h.paired, refreshDiscovery = true)
                runCurrent()
                assertEquals(2, h.client.attempts.size)
                assertEquals("1", h.latest.socket.request().url.queryParameter("refresh"))
                assertEquals(ConnectionState.Connecting, h.state)
            }
        }
    }

    @Test fun forget_clears_recovery_opportunity_and_ignores_late_callbacks_and_restoration() = runTest {
        RecoveryHarness(backgroundScope).use { h ->
            h.supervisor.focus("host", "session")
            h.connect()
            runCurrent()
            val forgotten = h.latest
            forgotten.synchronize()
            forgotten.frame("session_sync", "session")
            h.supervisor.setForeground(false)
            h.supervisor.setForeground(true)
            h.supervisor.forget("host")
            val statesBeforeLateCallbacks = h.states.toList()
            forgotten.open()
            forgotten.frame("host_sync")
            forgotten.fail()
            h.supervisor.setForeground(false)
            h.supervisor.setNetworkAvailable(false)
            h.supervisor.setForeground(true)
            h.supervisor.setNetworkAvailable(true)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, h.client.attempts.size)
            assertEquals(statesBeforeLateCallbacks, h.states)
            assertEquals(listOf("host_sync", "session_sync"), h.frames.map { it.optString("type") })
            assertFalse(h.supervisor.isDesired("host", "session"))
        }
    }

    @Test fun manual_refresh_supersedes_pending_attempt_and_late_callbacks_cannot_mutate_the_new_socket() = runTest {
        RecoveryHarness(backgroundScope).use { h ->
            val original = h.connect()
            runCurrent()
            val old = h.latest
            val refreshed = h.supervisor.connect(h.paired, refreshDiscovery = true)
            runCurrent()
            val replacement = h.latest
            assertTrue(original.isCompleted)
            assertTrue(old.socket.cancelled)
            assertFalse(refreshed.isCompleted)
            old.open()
            old.frame("host_sync")
            old.fail()
            assertEquals(ConnectionState.Connecting, h.state)
            assertTrue(h.frames.isEmpty())
            replacement.synchronize()
            assertTrue(refreshed.isCompleted)
            assertTrue(h.state is ConnectionState.Online)
            assertFalse(replacement.socket.cancelled)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(2, h.client.attempts.size)
            assertEquals("1", replacement.socket.request().url.queryParameter("refresh"))
        }
    }

    @Test fun manual_replacement_cannot_inherit_the_healthy_socket_restoration_opportunity() = runTest {
        RecoveryHarness(backgroundScope).use { h ->
            h.connect()
            runCurrent()
            val old = h.latest
            old.synchronize()
            h.supervisor.setForeground(false)
            h.supervisor.setForeground(true)
            h.supervisor.connect(h.paired)
            runCurrent()
            val replacement = h.latest
            old.fail()
            replacement.synchronize()
            replacement.fail()
            replacement.fail()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(2, h.client.attempts.size)
            assertTrue(h.state is ConnectionState.Offline)
        }
    }

    @Test fun connection_and_session_replay_timeouts_cannot_leave_synchronizing_forever() = runTest {
        for (open in listOf(false, true)) {
            RecoveryHarness(backgroundScope).use { h ->
                h.supervisor.focus("host", "session")
                val settled = h.connect()
                runCurrent()
                if (open) {
                    h.latest.open()
                    h.latest.frame("host_sync")
                    h.latest.frame("session_resume", "session")
                }
                advanceTimeBy(14_999)
                runCurrent()
                assertFalse(settled.isCompleted)
                assertEquals(if (open) ConnectionState.Synchronizing else ConnectionState.Connecting, h.state)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(settled.isCompleted)
                assertEquals(ConnectionState.Offline("Connection timed out", ConnectionFailure.TimedOut), h.state)
                assertTrue(h.latest.socket.cancelled)
                h.latest.fail()
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, h.client.attempts.size)

                h.supervisor.setForeground(false)
                h.supervisor.setForeground(true)
                runCurrent()
                assertEquals(2, h.client.attempts.size)
                if (open) {
                    h.latest.open()
                    h.latest.frame("host_sync")
                    h.latest.frame("session_resume", "session")
                }
                advanceTimeBy(14_999)
                runCurrent()
                assertEquals(if (open) ConnectionState.Synchronizing else ConnectionState.Connecting, h.state)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(ConnectionState.Offline("Connection timed out", ConnectionFailure.TimedOut), h.state)
                h.latest.fail()
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(2, h.client.attempts.size)
            }
        }
    }

    @Test fun online_waits_for_host_and_all_desired_session_boundaries_not_session_resume() = runTest {
        RecoveryHarness(backgroundScope).use { h ->
            h.supervisor.subscribe("host", "one")
            h.supervisor.subscribe("host", "two")
            val settled = h.connect()
            runCurrent()
            h.latest.open()
            h.latest.frame("session_snapshot", "one")
            h.latest.frame("host_sync")
            h.latest.frame("session_resume", "two")
            assertEquals(ConnectionState.Synchronizing, h.state)
            assertFalse(settled.isCompleted)
            h.latest.frame("session_sync", "two")
            assertTrue(h.state is ConnectionState.Online)
            assertTrue(settled.isCompleted)
            advanceTimeBy(15_000)
            runCurrent()
            assertFalse(h.latest.socket.cancelled)

            h.supervisor.subscribe("host", "three")
            assertEquals(ConnectionState.Synchronizing, h.state)
            h.supervisor.unsubscribe("host", "three")
            assertTrue(h.state is ConnectionState.Online)
            assertEquals(1, h.client.attempts.size)
        }
    }

    @Test fun successful_automatic_sync_does_not_grant_another_attempt_and_old_callbacks_are_isolated() = runTest {
        RecoveryHarness(backgroundScope).use { h ->
            h.client.beforeReturn = { it.synchronize() }
            h.connect()
            runCurrent()
            val first = h.latest
            h.supervisor.setForeground(false)
            h.supervisor.setForeground(true)
            first.fail()
            first.fail()
            runCurrent()
            assertEquals(2, h.client.attempts.size)
            assertTrue(h.state is ConnectionState.Online)
            val second = h.latest
            first.open()
            first.frame("host_sync")
            first.fail()
            h.supervisor.subscribe("host", "session")
            assertTrue(second.socket.messages.any { JSONObject(it).optString("type") == "subscribe" })
            assertTrue(first.socket.messages.isEmpty())
            second.frame("session_sync", "session")
            second.fail()
            repeat(3) {
                second.fail()
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(2, h.client.attempts.size)
                assertTrue(h.state is ConnectionState.Offline)
            }
        }
    }

    private class RecoveryHarness(scope: CoroutineScope) : AutoCloseable {
        val paired = PairedHost(Host("host", "Desktop", "linux", "", ""),
            "https://example.test", "token", "client")
        val client = ControlledSocketClient()
        val states = mutableListOf<ConnectionState>()
        val frames = mutableListOf<JSONObject>()
        var host = HostState(paired, connection = ConnectionState.Offline(), initialSync = InitialSyncState.Ready,
            serverEpoch = "epoch", wireSequence = 12, catalogVersion = "catalog")
        val state get() = host.connection
        val latest get() = client.attempts.last()
        private val transport = object : GatewayTransport by GatewayApi() { override val client = this@RecoveryHarness.client }
        val supervisor = HostConnectionSupervisor(scope, transport,
            { _, next -> states.add(next); host = host.withConnectionState(next) },
            { _, frame -> frames.add(frame) }, { _, _ -> }, cachedHost = { host })

        fun connect(foreground: Boolean = true) =
            supervisor.let { it.setForeground(foreground); it.connect(paired) }

        override fun close() {
            supervisor.forget("host")
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private class ControlledSocketClient : OkHttpClient() {
        val attempts = mutableListOf<SocketAttempt>()
        var beforeReturn: ((SocketAttempt) -> Unit)? = null

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val attempt = SocketAttempt(ControlledSocket(request), listener)
            attempts.add(attempt)
            beforeReturn?.invoke(attempt)
            return attempt.socket
        }
    }

    private class SocketAttempt(val socket: ControlledSocket, private val listener: WebSocketListener) {
        fun open() = listener.onOpen(socket, response(101))
        fun synchronize() { open(); frame("host_sync") }
        fun frame(type: String, sessionId: String? = null) {
            listener.onMessage(socket, JSONObject().put("type", type).put("epoch", "epoch")
                .apply { if (sessionId != null) put("sessionId", sessionId) }.toString())
        }
        fun fail(code: Int? = null) = listener.onFailure(socket, IOException("Disconnected"), code?.let(::response))
        private fun response(code: Int) = Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("Response").build()
    }

    private class ControlledSocket(private val request: Request) : WebSocket {
        val messages = mutableListOf<String>()
        var cancelled = false
        var closes = 0
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(text); return !cancelled }
        override fun send(bytes: ByteString) = !cancelled
        override fun close(code: Int, reason: String?): Boolean { closes++; return true }
        override fun cancel() { cancelled = true }
    }
}
