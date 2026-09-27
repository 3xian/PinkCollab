package dev.pinkcollab.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private class HistoryTransport(private val response: () -> String) : GatewayTransport {
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
            false, null, "", "", false), subscriptionId = "sub", savedHistory = SavedHistory.Loading,
    )

    private fun gatewayWith(state: MutableStateFlow<AppState>, transport: GatewayTransport) = SessionGateway(
        state, transport,
        { PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client") },
        { _, _ -> },
    )

    @Test fun history_page_becomes_ready() = runTest {
        val key = SessionKey("host", "session")
        val state = MutableStateFlow(AppState(details = mapOf(key to loadingDetail())))
        gatewayWith(state, HistoryTransport { historyPage }).loadHistory("host", "session", "sub")

        assertEquals(
            SavedHistory.Ready("source-one", listOf(TimelineItem("saved", "user", "hi", "", "")), null),
            state.value.details.getValue(key).savedHistory,
        )
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
        val state = MutableStateFlow(AppState(details = mapOf(key to loadingDetail().copy(subscriptionId = "new"))))
        gatewayWith(state, HistoryTransport { error("unexpected") }).loadHistory("host", "session", "old")

        assertEquals(SavedHistory.Loading, state.value.details.getValue(key).savedHistory)
    }

    @Test fun missing_subscription_fails_an_in_flight_page() = runTest {
        val key = SessionKey("host", "session")
        val state = MutableStateFlow(AppState(details = mapOf(key to loadingDetail().copy(subscriptionId = null))))
        val failure = runCatching {
            gatewayWith(state, HistoryTransport { error("unexpected") }).loadHistory("host", "session", null)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(SavedHistory.Failed, state.value.details.getValue(key).savedHistory)
    }

    @Test fun focusing_a_session_preserves_same_id_detail_on_another_host() = runTest {
        fun detail(hostId: String, subscription: String) = SessionDetail(
            Session("same", hostId, "/work", "Work", SessionStatus.Idle, "Ready",
                false, null, "", "", false), subscriptionId = subscription,
        )
        val first = SessionKey("host-a", "same")
        val second = SessionKey("host-b", "same")
        val state = MutableStateFlow(AppState(details = mapOf(first to detail("host-a", "old"), second to detail("host-b", "other"))))
        var focused = false
        val gateway = SessionGateway(state, NoNetworkTransport(), { error("unexpected host lookup") }) { hostId, sessionId ->
            assertEquals("host-a", hostId)
            assertEquals("same", sessionId)
            assertFalse(first in state.value.details)
            assertTrue(second in state.value.details)
            focused = true
            state.update { it.copy(details = it.details + (first to detail("host-a", "new"))) }
        }
        gateway.detail("host-a", "same")
        assertTrue(focused)
        assertEquals("new", state.value.details[first]?.subscriptionId)
        assertEquals("other", state.value.details[second]?.subscriptionId)
    }
}
