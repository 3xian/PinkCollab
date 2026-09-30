package dev.pinkcollab.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SessionGatewayTest {
    private class NoNetworkTransport : GatewayTransport {
        override val client = OkHttpClient()
        override fun validateURL(value: String) = value
        override suspend fun request(url: String, credential: String?, path: String, method: String,
            body: JSONObject?, query: Pair<String, String>?): String = error("unexpected request")
        override suspend fun upload(url: String, credential: String, path: String, name: String,
            bytes: ByteArray): String = error("unexpected upload")
    }

    private class HistoryTransport(private val response: suspend () -> String) : GatewayTransport {
        override val client = OkHttpClient()
        override fun validateURL(value: String) = value
        override suspend fun request(url: String, credential: String?, path: String, method: String,
            body: JSONObject?, query: Pair<String, String>?): String = response()
        override suspend fun upload(url: String, credential: String, path: String, name: String,
            bytes: ByteArray): String = error("unexpected upload")
    }

    private val historyPage =
        """{"source":{"id":"source-one"},"items":[{"id":"saved","kind":"user","text":"hi","detail":"","timestamp":""}]}"""

    private fun loadingDetail() = SessionDetail(
        Session("session", "host", "/work", "Work", SessionStatus.Idle, "Ready",
            false, null, "", "", false), snapshotToken = "sub", savedHistory = SavedHistory.Loading,
    )

    private fun gatewayWith(state: MutableStateFlow<AppState>, transport: GatewayTransport) = SessionGateway(
        state, transport,
        { PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client") },
        { _, _ -> },
    )

    @Test fun discovered_history_load_and_recoverable_error() = runTest {
        val key = SessionKey("host", "session")
        val initial = loadingDetail().let { it.copy(session = it.session.copy(origin = SessionOrigin.Discovered)) }
        val state = MutableStateFlow(AppState(details = mapOf(key to initial)))
        val failing = gatewayWith(state, HistoryTransport { throw GatewayHttpException(503, "history_unavailable", "History unavailable") })
        try { failing.loadHistory("host", "session", "sub"); error("Expected history failure") } catch (_: IOException) { }
        assertEquals(SavedHistory.Failed, state.value.details.getValue(key).savedHistory)
        gatewayWith(state, HistoryTransport { historyPage }).loadHistory("host", "session", "sub")
        assertTrue(state.value.details.getValue(key).savedHistory is SavedHistory.Ready)
        assertFalse(state.value.details.getValue(key).session.runtimeAttached)
    }

    @Test fun history_page_becomes_ready() = runTest {
        val key = SessionKey("host", "session")
        val state = MutableStateFlow(AppState(details = mapOf(key to loadingDetail())))
        gatewayWith(state, HistoryTransport { historyPage }).loadHistory("host", "session", "sub")

        assertEquals(
            SavedHistory.Ready("source-one", listOf(TimelineItem("saved", "user", "hi", "", "")), null),
            state.value.details.getValue(key).savedHistory,
        )
    }

    @Test fun first_prompt_waits_for_transcript_without_reporting_failure() = runTest {
        val key = SessionKey("host", "session")
        val initial = loadingDetail()
        val running = initial.copy(session = initial.session.copy(status = SessionStatus.Running,
            runtimeAttached = true))
        val state = MutableStateFlow(AppState(details = mapOf(key to running)))
        var requests = 0
        val gateway = gatewayWith(state, HistoryTransport {
            if (++requests == 1) throw GatewayHttpException(503, "history_unavailable", "OMP history is unavailable")
            historyPage
        })
        val pending = async { gateway.loadHistory("host", "session", "sub") }
        yield()
        assertFalse(pending.isCompleted)
        assertEquals(SavedHistory.Loading, state.value.details.getValue(key).savedHistory)
        state.update { it.copy(details = mapOf(key to running.copy(session = running.session.copy(
            status = SessionStatus.Idle)))) }
        pending.await()
        assertEquals(2, requests)
        assertTrue(state.value.details.getValue(key).savedHistory is SavedHistory.Ready)
    }

    @Test fun unavailable_running_history_times_out_without_losing_live_messages() = runTest {
        val key = SessionKey("host", "session")
        val initial = loadingDetail()
        val live = TimelineItem("live", "assistant", "Working", "", "")
        val running = initial.copy(session = initial.session.copy(status = SessionStatus.Running,
            runtimeAttached = true), liveItems = listOf(live))
        val state = MutableStateFlow(AppState(details = mapOf(key to running)))
        val gateway = gatewayWith(state, HistoryTransport {
            throw GatewayHttpException(503, "history_unavailable", "Unavailable")
        })
        try {
            gateway.loadHistory("host", "session", "sub")
            error("Expected timeout")
        } catch (_: IOException) { }
        assertEquals(SavedHistory.Failed, state.value.details.getValue(key).savedHistory)
        assertEquals(listOf(live), state.value.details.getValue(key).liveItems)
        gatewayWith(state, HistoryTransport { historyPage }).loadHistory("host", "session", "sub")
        assertTrue(state.value.details.getValue(key).savedHistory is SavedHistory.Ready)
    }

    @Test fun unavailable_active_history_finishes_when_focus_moves_to_another_host() = runTest {
        val key = SessionKey("host", "session")
        val state = MutableStateFlow(AppState())
        var requests = 0
        val transport = HistoryTransport {
            requests++
            throw GatewayHttpException(503, "history_unavailable", "OMP history is unavailable")
        }
        val gateway = gatewayWith(state, transport)
        val connections = HostConnectionSupervisor(this, transport, { _, _, _ -> }, { _, _ -> },
            gateway::invalidateSubscription)
        connections.focus("host", "session")
        val initial = loadingDetail()
        val running = initial.copy(session = initial.session.copy(status = SessionStatus.Running,
            runtimeAttached = true),
            liveItems = listOf(TimelineItem("live", "assistant", "Working", "", "")))
        state.value = AppState(details = mapOf(key to running))
        val pending = async { gateway.loadHistory("host", "session", "sub") }
        yield()
        assertFalse(pending.isCompleted)
        assertEquals(1, requests)

        connections.focus("another-host", "session")
        yield()

        assertTrue(pending.isCompleted)
        pending.await()
        val retained = state.value.details.getValue(key)
        assertNull(retained.snapshotToken)
        assertEquals(running.session, retained.session)
        assertEquals(running.liveItems, retained.liveItems)
        assertEquals(SavedHistory.Loading, retained.savedHistory)
        assertEquals(1, requests)
        assertFalse(connections.isDesired("host", "session"))
        assertTrue(connections.isDesired("another-host", "session"))
    }

    @Test fun unsubscribe_and_disconnect_keep_the_visible_transcript() = runTest {
        val key = SessionKey("host", "session")
        val ready = loadingDetail().copy(
            savedHistory = SavedHistory.Ready("source", listOf(TimelineItem("saved", "user", "Keep me", "", "")), "older"))
        for (disconnect in listOf("unsubscribe", "offline", "forget")) {
            val state = MutableStateFlow(AppState())
            val transport = NoNetworkTransport()
            val gateway = gatewayWith(state, transport)
            val connections = HostConnectionSupervisor(this, transport, { _, _, _ -> }, { _, _ -> },
                gateway::invalidateSubscription)
            connections.focus("host", "session")
            state.value = AppState(details = mapOf(key to ready))

            when (disconnect) {
                "unsubscribe" -> connections.unsubscribe("host", "session")
                "offline" -> connections.networkUnavailable(listOf("host"))
                "forget" -> connections.forget("host")
            }

            val retained = state.value.details.getValue(key)
            assertEquals(ready.savedHistory, retained.savedHistory)
            assertEquals(ready.session, retained.session)
            assertNull(retained.snapshotToken)
            assertEquals(disconnect == "offline", connections.isDesired("host", "session"))
        }
    }

    @Test fun in_flight_history_cannot_overwrite_a_refocused_subscription() = runTest {
        val key = SessionKey("host", "session")
        val response = CompletableDeferred<String>()
        val state = MutableStateFlow(AppState())
        val transport = HistoryTransport { response.await() }
        val gateway = gatewayWith(state, transport)
        val connections = HostConnectionSupervisor(this, transport, { _, _, _ -> }, { _, _ -> },
            gateway::invalidateSubscription)
        connections.focus("host", "session")
        state.value = AppState(details = mapOf(key to loadingDetail()))
        val pending = async { gateway.loadHistory("host", "session", "sub") }
        yield()
        assertFalse(pending.isCompleted)
        connections.unsubscribe("host", "session")
        connections.focus("host", "session")
        val fresh = loadingDetail().copy(snapshotToken = "new-sub",
            savedHistory = SavedHistory.Ready("new-source", listOf(TimelineItem("new", "user", "New", "", "")), null))
        state.value = AppState(details = mapOf(key to fresh))
        response.complete(historyPage)
        pending.await()

        assertEquals(fresh, state.value.details.getValue(key))
    }

    @Test fun genuinely_missing_history_still_fails_after_settling() = runTest {
        val key = SessionKey("host", "session")
        val state = MutableStateFlow(AppState(details = mapOf(key to loadingDetail())))
        var requests = 0
        val failure = runCatching {
            gatewayWith(state, HistoryTransport {
                requests++
                throw GatewayHttpException(503, "history_unavailable", "OMP history is unavailable")
            }).loadHistory("host", "session", "sub")
        }.exceptionOrNull()
        assertTrue(failure is GatewayHttpException)
        assertEquals(3, requests)
        assertEquals(SavedHistory.Failed, state.value.details.getValue(key).savedHistory)
    }

    @Test fun failed_history_request_is_not_an_empty_transcript() = runTest {
        val key = SessionKey("host", "session")
        val state = MutableStateFlow(AppState(details = mapOf(key to loadingDetail())))
        val failure = runCatching {
            gatewayWith(state, HistoryTransport { throw IOException("offline") })
                .loadHistory("host", "session", "sub")
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(SavedHistory.Failed, state.value.details.getValue(key).savedHistory)
    }

    @Test fun failed_reload_keeps_a_visible_page() = runTest {
        val key = SessionKey("host", "session")
        val ready = SavedHistory.Ready("source-one", listOf(TimelineItem("saved", "user", "hi", "", "")), null)
        val state = MutableStateFlow(AppState(details = mapOf(key to loadingDetail().copy(savedHistory = ready))))
        val failure = runCatching {
            gatewayWith(state, HistoryTransport { throw IOException("offline") }).loadHistory("host", "session", "sub")
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(ready, state.value.details.getValue(key).savedHistory)
    }

    @Test fun stale_history_request_does_not_fail_a_newer_subscription() = runTest {
        val key = SessionKey("host", "session")
        val state = MutableStateFlow(AppState(details = mapOf(key to loadingDetail().copy(snapshotToken = "new"))))
        gatewayWith(state, HistoryTransport { error("unexpected") }).loadHistory("host", "session", "old")

        assertEquals(SavedHistory.Loading, state.value.details.getValue(key).savedHistory)
    }

    @Test fun missing_subscription_waits_for_reconnection_without_requesting_history() = runTest {
        val key = SessionKey("host", "session")
        val state = MutableStateFlow(AppState(details = mapOf(key to loadingDetail().copy(snapshotToken = null))))
        gatewayWith(state, HistoryTransport { error("unexpected") }).loadHistory("host", "session", null)
        assertEquals(SavedHistory.Loading, state.value.details.getValue(key).savedHistory)
    }

    @Test fun focusing_a_session_preserves_same_id_detail_on_another_host() = runTest {
        fun detail(hostId: String, subscription: String) = SessionDetail(
            Session("same", hostId, "/work", "Work", SessionStatus.Idle, "Ready",
                false, null, "", "", false), snapshotToken = subscription,
        )
        val first = SessionKey("host-a", "same")
        val second = SessionKey("host-b", "same")
        val state = MutableStateFlow(AppState(details = mapOf(first to detail("host-a", "old"), second to detail("host-b", "other"))))
        var focused = false
        val gateway = SessionGateway(state, NoNetworkTransport(), { error("unexpected host lookup") }) { hostId, sessionId ->
            assertEquals("host-a", hostId)
            assertEquals("same", sessionId)
            assertEquals(detail("host-a", "old").copy(snapshotToken = null), state.value.details[first])
            assertTrue(second in state.value.details)
            focused = true
            state.update { it.copy(details = it.details + (first to detail("host-a", "new"))) }
        }
        gateway.detail("host-a", "same")
        assertTrue(focused)
        assertEquals("new", state.value.details[first]?.snapshotToken)
        assertEquals("other", state.value.details[second]?.snapshotToken)
    }
}
