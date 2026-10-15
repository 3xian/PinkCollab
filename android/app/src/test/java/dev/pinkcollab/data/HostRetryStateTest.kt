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
    @Test fun rejected_open_stays_offline_without_another_attempt() = runBlocking {
        val transport = object : GatewayTransport by GatewayApi() {
            override val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(503).message("Unavailable").body("".toResponseBody()).build()
            }.build()
        }
        val states = Channel<ConnectionState>(Channel.UNLIMITED)
        val supervisor = HostConnectionSupervisor(this, transport,
            { _, state -> states.trySend(state) }, { _, _ -> }, { _, _ -> })
        val paired = PairedHost(Host("host", "Desktop", "windows", "", ""),
            "https://example.test", "token", "client")
        try {
            supervisor.connect(paired)
            withTimeout(5_000) {
                assertEquals(ConnectionState.Connecting, states.receive())
                val offline = states.receive() as ConnectionState.Offline
                assertEquals(ConnectionFailure.HostRejected, offline.failure)
                assertEquals("Host rejected the connection (HTTP 503)", offline.reason)
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
