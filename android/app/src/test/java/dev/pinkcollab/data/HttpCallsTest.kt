package dev.pinkcollab.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HttpCallsTest {
    @Test fun cancelling_a_suspended_request_cancels_its_http_call() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val requestReceived = CountDownLatch(1)
            val releaseServer = CountDownLatch(1)
            val responder = thread {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().readLine()
                    requestReceived.countDown()
                    releaseServer.await(5, TimeUnit.SECONDS)
                }
            }
            try {
                val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
                val call = client.newCall(Request.Builder()
                    .url("http://127.0.0.1:${server.localPort}/").build())
                val pending = async(Dispatchers.IO) {
                    call.awaitSuccessfulBody { code, _ -> IOException("HTTP $code") }
                }
                assertTrue("HTTP request was not received", requestReceived.await(3, TimeUnit.SECONDS))
                pending.cancelAndJoin()
                assertTrue("The underlying HTTP call was not cancelled", call.isCanceled())
            } finally {
                releaseServer.countDown()
                server.close()
                responder.join(3000)
            }
        }
    }
}
