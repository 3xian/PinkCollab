package dev.pinkcollab.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionCatalogTest {
    private val paired = PairedHost(Host("h", "host", "", "", ""), "https://host", "token", "client")
    private val page = """{"epoch":"e","sessions":[{"session":{"id":"s","hostId":"h","cwd":"/work","title":"old response","createdAt":"","updatedAt":""},"runtime":null}],"nextCursor":null}"""
    private class Transport(val response: suspend () -> String) : GatewayTransport {
        override val client = OkHttpClient()
        override fun validateURL(value: String) = value
        override suspend fun request(url: String, credential: String?, path: String, method: String, body: JSONObject?, query: Pair<String, String>?) = response()
        override suspend fun upload(url: String, credential: String, path: String, name: String, bytes: ByteArray): String = error("unexpected")
    }
    private fun state() = MutableStateFlow(AppState(hosts = mapOf("h" to HostState(paired, snapshotToken = "one", serverEpoch = "e", nextSessionsCursor = "cursor", totalSessions = 70))))

    @Test fun page_read_keeps_newer_socket_state_and_finishes_the_cursor() = runTest {
        val state = state()
        val release = CompletableDeferred<Unit>()
        val catalog = SessionCatalog(state, Transport { release.await(); page })
        val load = async { catalog.loadMore("h") }
        yield()
        val latest = Session("s", "h", "/work", "socket title", SessionStatus.Running, "Working", false, null, "", "", true, generation = "g")
        state.update { it.copy(hosts = mapOf("h" to it.hosts.getValue("h").copy(sessions = listOf(latest)))) }
        release.complete(Unit)
        load.await()
        assertEquals(listOf(latest), state.value.hosts.getValue("h").sessions)
        assertNull(state.value.hosts.getValue("h").nextSessionsCursor)
        assertEquals(70, state.value.hosts.getValue("h").totalSessions)
    }

    @Test fun replacement_snapshot_discards_an_old_catalog_page() = runTest {
        val state = state()
        val release = CompletableDeferred<Unit>()
        val catalog = SessionCatalog(state, Transport { release.await(); page })
        val load = async { catalog.loadMore("h") }
        yield()
        state.update { it.copy(hosts = mapOf("h" to it.hosts.getValue("h").copy(snapshotToken = "new", nextSessionsCursor = "new-cursor"))) }
        release.complete(Unit)
        load.await()
        assertTrue(state.value.hosts.getValue("h").sessions.isEmpty())
        assertEquals("new-cursor", state.value.hosts.getValue("h").nextSessionsCursor)
    }
}
