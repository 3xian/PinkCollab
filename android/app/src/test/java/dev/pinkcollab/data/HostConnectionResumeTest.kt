package dev.pinkcollab.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
class HostConnectionResumeTest {
    @Test fun foreground_resume_preserves_live_connection_and_recovers_after_network_loss() = runBlocking {
        val server = MockWebServer()
        val sockets = Channel<WebSocket>(Channel.UNLIMITED)
        val messages = Channel<String>(Channel.UNLIMITED)
        repeat(3) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { sockets.trySend(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) { messages.trySend(text) }
            }))
        }
        server.start()
        val client = OkHttpClient()
        val transport = object : GatewayTransport by GatewayApi() { override val client = client }
        val states = Channel<ConnectionState>(Channel.UNLIMITED)
        val frames = Channel<String>(Channel.UNLIMITED)
        var invalidations = 0
        val supervisor = HostConnectionSupervisor(this, transport,
            { _, state, _ -> states.trySend(state) },
            { _, frame -> frames.trySend(frame.getString("type")) },
            { _, _ -> invalidations++ })
        val paired = PairedHost(Host("host", "Desktop", "linux", "", ""),
            server.url("/").toString().trimEnd('/'), "token", "client")
        try {
            supervisor.focus("host", "session")
            supervisor.connect(paired)
            val socket = withTimeout(5_000) {
                assertEquals(ConnectionState.Connecting, states.receive())
                assertEquals(ConnectionState.Synchronizing, states.receive())
                assertEquals("subscribe", org.json.JSONObject(messages.receive()).getString("type"))
                sockets.receive()
            }
            val before = invalidations
            supervisor.connect(paired, force = false)
            socket.send("{\"type\":\"host_sync\"}")
            withTimeout(5_000) { assertEquals("host_sync", frames.receive()) }
            assertEquals(before, invalidations)
            assertEquals(1, server.requestCount)
            assertTrue(states.tryReceive().isFailure)

            supervisor.connect(paired, force = true)
            withTimeout(5_000) {
                assertEquals(ConnectionState.Connecting, states.receive())
                assertEquals(ConnectionState.Synchronizing, states.receive())
                assertEquals("subscribe", org.json.JSONObject(messages.receive()).getString("type"))
                sockets.receive().send("{\"type\":\"host_sync\"}")
                assertEquals("host_sync", frames.receive())
            }
            assertEquals(before + 1, invalidations)
            assertEquals(2, server.requestCount)

            supervisor.networkUnavailable(listOf("host"))
            assertTrue(states.receive() is ConnectionState.Offline)
            supervisor.connect(paired, force = false)
            withTimeout(5_000) {
                assertEquals(ConnectionState.Connecting, states.receive())
                assertEquals(ConnectionState.Synchronizing, states.receive())
                assertEquals("subscribe", org.json.JSONObject(messages.receive()).getString("type"))
                sockets.receive().send("{\"type\":\"host_sync\"}")
                assertEquals("host_sync", frames.receive())
            }
            assertEquals(3, server.requestCount)
        } finally {
            supervisor.forget("host")
            server.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
