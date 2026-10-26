package dev.pinkcollab.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HostConnectionResumeTest {
    @Test fun only_explicit_refresh_adds_discovery_bypass_and_preserves_resume_parameters() = runBlocking {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.start()
        val client = OkHttpClient()
        val transport = object : GatewayTransport by GatewayApi() { override val client = client }
        val paired = PairedHost(Host("host", "Desktop", "linux", "", ""),
            server.url("/").toString().trimEnd('/'), "token", "client")
        val cached = HostState(paired, serverEpoch = "e", wireSequence = 12, catalogVersion = "v")
        val supervisor = HostConnectionSupervisor(this, transport, { _, _ -> }, { _, _ -> }, { _, _ -> },
            cachedHost = { cached })
        try {
            withTimeout(5_000) { supervisor.connect(paired).await() }
            withTimeout(5_000) { supervisor.connect(paired, refreshDiscovery = true).await() }
            val ordinary = server.takeRequest().requestUrl!!
            val refreshed = server.takeRequest().requestUrl!!
            assertNull(ordinary.queryParameter("refresh"))
            assertEquals("1", refreshed.queryParameter("refresh"))
            for (url in listOf(ordinary, refreshed)) {
                assertEquals("e", url.queryParameter("epoch"))
                assertEquals("12", url.queryParameter("sequence"))
                assertEquals("v", url.queryParameter("catalog"))
            }
        } finally {
            supervisor.forget("host")
            server.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun repeated_equal_failures_complete_each_attempt_without_state_collectors() = runBlocking {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.start()
        val client = OkHttpClient()
        val transport = object : GatewayTransport by GatewayApi() { override val client = client }
        var state: ConnectionState = ConnectionState.Offline()
        val supervisor = HostConnectionSupervisor(this, transport,
            { _, next -> state = next }, { _, _ -> }, { _, _ -> })
        val paired = PairedHost(Host("host", "Desktop", "linux", "", ""),
            server.url("/").toString().trimEnd('/'), "token", "client")
        try {
            withTimeout(5_000) { supervisor.connect(paired).await() }
            val firstFailure = state
            assertEquals(ConnectionState.Offline("Host rejected the connection (HTTP 503)",
                ConnectionFailure.HostRejected), firstFailure)
            withTimeout(5_000) { supervisor.connect(paired).await() }
            assertEquals(firstFailure, state)
            assertEquals(2, server.requestCount)
        } finally {
            supervisor.forget("host")
            server.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun foreground_recovery_resumes_host_and_session_sequences_without_discarding_loaded_history() = runBlocking {
        val server = MockWebServer()
        val sockets = Channel<WebSocket>(Channel.UNLIMITED)
        val subscriptions = Channel<JSONObject>(Channel.UNLIMITED)
        repeat(2) { index ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets.trySend(webSocket)
                    webSocket.send(JSONObject().put("type", "host_sync").put("protocolVersion", 4)
                        .put("epoch", "epoch").put("sequence", 20 + index * 10).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val command = JSONObject(text)
                    subscriptions.trySend(command)
                    webSocket.send(JSONObject().put("type", "session_resume").put("sessionId", "session")
                        .put("epoch", "epoch").put("sequence", command.getLong("sequence")).toString())
                    webSocket.send(JSONObject().put("type", "timeline").put("sessionId", "session")
                        .put("epoch", "epoch").put("sequence", 22 + index * 10)
                        .put("remove", JSONArray()).put("upsert", JSONArray().put(JSONObject()
                            .put("id", "live-$index").put("kind", "assistant").put("text", "Reply $index")
                            .put("timestamp", ""))).toString())
                    webSocket.send(JSONObject().put("type", "session_sync").put("sessionId", "session")
                        .put("epoch", "epoch").put("sequence", 23 + index * 10).toString())
                }
            }))
        }
        server.start()
        val client = OkHttpClient()
        val transport = object : GatewayTransport by GatewayApi() { override val client = client }
        val paired = PairedHost(Host("host", "Desktop", "linux", "", ""),
            server.url("/").toString().trimEnd('/'), "token", "client")
        val session = Session("session", "host", "/work", "Work", SessionStatus.Idle, "", false, null, "", "", false)
        val saved = SavedHistory.Ready("source", listOf(TimelineItem("saved", "user", "Keep me", "", "")), "older")
        val key = SessionKey("host", "session")
        val app = MutableStateFlow(AppState(
            hosts = mapOf("host" to HostState(paired, sessions = listOf(session), initialSync = InitialSyncState.Ready,
                serverEpoch = "epoch", wireSequence = 12, catalogVersion = "catalog")),
            details = mapOf(key to SessionDetail(session, savedHistory = saved, serverEpoch = "epoch", wireSequence = 7)),
        ))
        val states = Channel<ConnectionState>(Channel.UNLIMITED)
        val supervisor = HostConnectionSupervisor(this, transport,
            { id, connection ->
                app.update { it.copy(hosts = it.hosts + (id to it.hosts.getValue(id).withConnectionState(connection))) }
                states.trySend(connection)
            },
            { id, frame -> app.update { reduceProtocol(it, id, frame, 1).state } },
            { _, _ -> app.update { it.copy(details = it.details + (key to it.details.getValue(key).copy(snapshotToken = null))) } },
            cachedHost = { app.value.hosts[it] }, cachedSession = { _, _ -> app.value.details[key] })
        try {
            supervisor.focus("host", "session")
            withTimeout(5_000) { supervisor.connect(paired, refreshDiscovery = true).await() }
            val firstSocket = withTimeout(5_000) { sockets.receive() }
            val firstRequest = server.takeRequest().requestUrl!!
            val firstSubscription = withTimeout(5_000) { subscriptions.receive() }
            assertEquals("1", firstRequest.queryParameter("refresh"))
            assertEquals("12", firstRequest.queryParameter("sequence"))
            assertEquals(7L, firstSubscription.getLong("sequence"))
            assertEquals(listOf("Reply 0"), app.value.details.getValue(key).liveItems.map { it.text })

            firstSocket.close(1000, "background disconnect")
            withTimeout(5_000) { while (states.receive() !is ConnectionState.Offline) { } }
            assertEquals(1, server.requestCount)
            assertEquals(saved, app.value.details.getValue(key).savedHistory)
            assertEquals(InitialSyncState.Ready, app.value.hosts.getValue("host").initialSync)
            assertNull(app.value.details.getValue(key).snapshotToken)

            supervisor.setForeground(true)
            withTimeout(5_000) { while (states.receive() !is ConnectionState.Online) { } }
            val recoveredSocket = withTimeout(5_000) { sockets.receive() }
            val recoveredRequest = server.takeRequest().requestUrl!!
            val recoveredSubscription = withTimeout(5_000) { subscriptions.receive() }
            assertEquals(2, server.requestCount)
            assertNull(recoveredRequest.queryParameter("refresh"))
            assertEquals("epoch", recoveredRequest.queryParameter("epoch"))
            assertEquals("20", recoveredRequest.queryParameter("sequence"))
            assertEquals("catalog", recoveredRequest.queryParameter("catalog"))
            assertEquals("subscribe", recoveredSubscription.getString("type"))
            assertEquals("epoch", recoveredSubscription.getString("epoch"))
            assertEquals(23L, recoveredSubscription.getLong("sequence"))
            assertTrue(recoveredSubscription.getBoolean("hasCachedHistory"))
            assertEquals(saved, app.value.details.getValue(key).savedHistory)
            assertEquals(listOf("Reply 0", "Reply 1"), app.value.details.getValue(key).liveItems.map { it.text })
            assertEquals(33L, app.value.details.getValue(key).wireSequence)
            assertNotNull(app.value.details.getValue(key).snapshotToken)

            recoveredSocket.close(1000, "recovered connection ended")
            withTimeout(5_000) { while (states.receive() !is ConnectionState.Offline) { } }
            assertEquals(2, server.requestCount)
            assertEquals(saved, app.value.details.getValue(key).savedHistory)
            assertEquals(listOf("Reply 0", "Reply 1"), app.value.details.getValue(key).liveItems.map { it.text })
            assertEquals(33L, app.value.details.getValue(key).wireSequence)
            assertEquals(InitialSyncState.Ready, app.value.hosts.getValue("host").initialSync)
            assertNull(app.value.details.getValue(key).snapshotToken)
        } finally {
            supervisor.forget("host")
            server.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
