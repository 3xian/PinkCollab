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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HostConnectionResumeTest {
    @Test fun closed_socket_stays_offline_until_an_explicit_connect() = runBlocking {
        val server = MockWebServer()
        val sockets = Channel<WebSocket>(Channel.UNLIMITED)
        val messages = Channel<String>(Channel.UNLIMITED)
        repeat(2) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { sockets.trySend(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) { messages.trySend(text) }
            }))
        }
        server.start()
        val client = OkHttpClient()
        val transport = object : GatewayTransport by GatewayApi() { override val client = client }
        val states = Channel<ConnectionState>(Channel.UNLIMITED)
        val supervisor = HostConnectionSupervisor(this, transport,
            { _, state -> states.trySend(state) }, { _, _ -> }, { _, _ -> })
        val paired = PairedHost(Host("host", "Desktop", "linux", "", ""),
            server.url("/").toString().trimEnd('/'), "token", "client")
        try {
            supervisor.focus("host", "session")
            supervisor.connect(paired)
            val socket = withTimeout(5_000) {
                assertEquals(ConnectionState.Connecting, states.receive())
                assertEquals(ConnectionState.Synchronizing, states.receive())
                assertEquals("subscribe", JSONObject(messages.receive()).getString("type"))
                sockets.receive()
            }
            socket.close(1000, "gone")
            withTimeout(5_000) {
                assertTrue(states.receive() is ConnectionState.Offline)
            }
            assertEquals(1, server.requestCount)
            assertTrue(states.tryReceive().isFailure)

            supervisor.connect(paired)
            withTimeout(5_000) {
                assertEquals(ConnectionState.Connecting, states.receive())
                assertEquals(ConnectionState.Synchronizing, states.receive())
                sockets.receive()
            }
            assertEquals(2, server.requestCount)
        } finally {
            supervisor.forget("host")
            server.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
