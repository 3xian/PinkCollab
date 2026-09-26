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

class SessionGatewayTest {
    private class NoNetworkTransport : GatewayTransport {
        override val client = OkHttpClient()
        override fun validateURL(value: String) = value
        override suspend fun request(url: String, credential: String?, path: String, method: String,
            body: JSONObject?, query: Pair<String, String>?): String = error("unexpected request")
        override suspend fun upload(url: String, credential: String, path: String, name: String,
            bytes: ByteArray): String = error("unexpected upload")
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
