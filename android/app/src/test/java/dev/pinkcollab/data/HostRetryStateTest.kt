package dev.pinkcollab.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class HostRetryStateTest {
    @Test fun retry_attempts_are_connecting_and_backoff_is_offline() = runBlocking {
        val transport = object : GatewayTransport by GatewayApi() {
            override val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(503).message("Unavailable").body("".toResponseBody()).build()
            }.build()
        }
        val states = Channel<Pair<ConnectionState, ConnectionProgress?>>(Channel.UNLIMITED)
        val supervisor = HostConnectionSupervisor(this, transport,
            { _, state, progress -> states.trySend(state to progress) }, { _, _ -> }, { _, _ -> })
        val paired = PairedHost(Host("host", "Desktop", "windows", "", ""),
            "https://example.test", "token", "client")
        try {
            supervisor.connect(paired)
            withTimeout(5_000) {
                val first = states.receive()
                assertEquals(ConnectionState.Connecting, first.first)
                assertEquals(1, first.second?.attempt)
                val waiting = states.receive()
                assertTrue(waiting.first is ConnectionState.Offline)
                assertEquals(ConnectionFailure.HostRejected, (waiting.first as ConnectionState.Offline).failure)
                assertEquals(2, waiting.second?.attempt)
                val retry = states.receive()
                assertEquals(ConnectionState.Connecting, retry.first)
                assertEquals(2, retry.second?.attempt)
                assertEquals("Host rejected the connection (HTTP 503)", retry.second?.failure)
                assertTrue(states.receive().first is ConnectionState.Offline)
            }
        } finally {
            supervisor.forget("host")
            transport.client.dispatcher.executorService.shutdown()
            transport.client.connectionPool.evictAll()
            states.close()
        }
    }
}
