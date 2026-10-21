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
    @Test fun deleted_session_cannot_be_resurrected_by_an_inflight_page() = runTest {
        val state = state()
        val release = CompletableDeferred<Unit>()
        val catalog = SessionCatalog(state, Transport { release.await(); page })
        val load = async { catalog.loadMore("h") }
        yield()
        state.value = reduceProtocol(state.value, "h", JSONObject("""{"type":"session_remove","sessionId":"s","epoch":"e","sequence":1}"""), 0).state
        release.complete(Unit)
        load.await()
        assertTrue(state.value.hosts.getValue("h").sessions.isEmpty())
    }

    @Test fun repairing_during_a_page_discards_the_result() = runTest {
        val state = state()
        val release = CompletableDeferred<Unit>()
        val catalog = SessionCatalog(state, Transport { release.await(); page })
        val load = async { catalog.loadMore("h") }
        yield()
        state.update { app -> app.copy(hosts = mapOf("h" to app.hosts.getValue("h")
            .copy(paired = paired.copy(credential = "new")))) }
        release.complete(Unit)
        load.await()
        assertTrue(state.value.hosts.getValue("h").sessions.isEmpty())
        assertEquals("cursor", state.value.hosts.getValue("h").nextSessionsCursor)
    }

    @Test fun unrelated_catalog_activity_does_not_stall_paging_or_selection() = runTest {
        for (individual in listOf(false, true)) {
            val state = state()
            val release = CompletableDeferred<Unit>()
            val response = if (individual) JSONObject(page).getJSONArray("sessions").getJSONObject(0).toString() else page
            val catalog = SessionCatalog(state, Transport { release.await(); response })
            val load = async { if (individual) catalog.ensureListed(SessionKey("h", "s")) else catalog.loadMore("h") }
            yield()
            state.update { app -> app.copy(hosts = mapOf("h" to app.hosts.getValue("h").copy(catalogVersion = "changed"))) }
            release.complete(Unit)
            load.await()
            assertEquals("s", state.value.hosts.getValue("h").sessions.single().id)
            if (!individual) assertNull(state.value.hosts.getValue("h").nextSessionsCursor)
        }
    }

    @Test fun paging_tracks_cache_provenance_under_the_current_catalog() = runTest {
        val state = state()
        state.update { it.copy(hosts = mapOf("h" to it.hosts.getValue("h").copy(catalogVersion = "v"))) }
        SessionCatalog(state, Transport { JSONObject(page).put("catalogVersion", "v").toString() }).loadMore("h")
        val host = state.value.hosts.getValue("h")
        assertEquals(setOf("s"), host.pagedSessionIds)
        assertEquals("v", host.pagesCatalogVersion)
    }

    @Test fun only_pages_with_server_provenance_survive_matching_snapshots() = runTest {
        // A different Discovery generation can appear in HTTP without any socket event.
        for (responseVersion in listOf(null, "other", "v")) {
            val state = state()
            state.update { it.copy(hosts = mapOf("h" to it.hosts.getValue("h").copy(catalogVersion = "v"))) }
            val response = JSONObject(page).apply { responseVersion?.let { put("catalogVersion", it) } }.toString()
            SessionCatalog(state, Transport { response }).loadMore("h")
            assertEquals(responseVersion, state.value.hosts.getValue("h").pagesCatalogVersion)
            val snapshot = JSONObject().put("type", "host_snapshot").put("protocolVersion", 4)
                .put("host", paired.host.json()).put("epoch", "e").put("sequence", 0).put("catalogVersion", "v")
                .put("sessions", org.json.JSONArray()).put("workspaces", org.json.JSONArray()).put("nextSessionsCursor", "base")
            val after = reduceProtocol(state.value, "h", snapshot, 0).state.hosts.getValue("h")
            assertEquals(responseVersion == "v", after.sessions.any { it.id == "s" })
            assertEquals(if (responseVersion == "v") null else "base", after.nextSessionsCursor)
        }
    }

    @Test fun mixed_page_versions_cannot_recover_false_provenance() = runTest {
        val state = state()
        val versions = ArrayDeque(listOf("v", "other", "v"))
        val catalog = SessionCatalog(state, Transport {
            val version = versions.removeFirst()
            JSONObject(page).put("catalogVersion", version).put("nextCursor", "next-${versions.size}").toString()
        })
        catalog.loadMore("h")
        assertEquals("v", state.value.hosts.getValue("h").pagesCatalogVersion)
        catalog.loadMore("h")
        assertNull(state.value.hosts.getValue("h").pagesCatalogVersion)
        catalog.loadMore("h")
        assertNull(state.value.hosts.getValue("h").pagesCatalogVersion)
    }

    @Test fun deleted_or_replaced_session_reads_still_cannot_apply() = runTest {
        for (replacement in listOf("delete", "snapshot", "pair", "epoch")) {
            val state = state()
            val release = CompletableDeferred<Unit>()
            val catalog = SessionCatalog(state, Transport {
                release.await(); JSONObject(page).getJSONArray("sessions").getJSONObject(0).toString()
            })
            val load = async { catalog.ensureListed(SessionKey("h", "s")) }
            yield()
            state.update { app ->
                val host = app.hosts.getValue("h")
                val changed = when (replacement) {
                    "delete" -> host.copy(removedSessionIds = setOf("s"))
                    "snapshot" -> host.copy(snapshotToken = "new")
                    "pair" -> host.copy(paired = paired.copy(credential = "new"))
                    else -> host.copy(serverEpoch = "new")
                }
                app.copy(hosts = mapOf("h" to changed))
            }
            release.complete(Unit)
            load.await()
            assertTrue(replacement, state.value.hosts.getValue("h").sessions.isEmpty())
        }
    }

}
