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
    @Test fun connection_cycle_tries_three_times_then_stays_offline() = runBlocking {
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
                repeat(3) { index ->
                    val attempt = index + 1
                    val connecting = states.receive()
                    assertEquals(ConnectionState.Connecting, connecting.first)
                    assertEquals(attempt, connecting.second?.attempt)
                    supervisor.connect(paired, force = false)
                    val expectedFailure = if (attempt == 1) null else "Host rejected the connection (HTTP 503)"
                    assertEquals(expectedFailure, connecting.second?.failure)
                    val offline = states.receive()
                    val failure = offline.first as ConnectionState.Offline
                    assertEquals(ConnectionFailure.HostRejected, failure.failure)
                    assertEquals("Host rejected the connection (HTTP 503)", failure.reason)
                    assertNull(offline.second)
                }
                assertTrue(states.tryReceive().isFailure)
            }
        } finally {
            supervisor.forget("host")
            transport.client.dispatcher.executorService.shutdown()
            transport.client.connectionPool.evictAll()
            states.close()
        }
    }
}
