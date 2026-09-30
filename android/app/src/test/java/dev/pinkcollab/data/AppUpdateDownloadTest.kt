package dev.pinkcollab.data

import dev.pinkcollab.ui.AppUpdateDownload
import dev.pinkcollab.ui.UpdateDownloadState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppUpdateDownloadTest {
    @Test fun worker_progress_is_ordered_with_cancellation_and_cannot_update_a_new_attempt() = runTest {
        val callbacks = mutableListOf<(Long, Long) -> Unit>()
        val download = AppUpdateDownload(backgroundScope) { _, progress ->
            callbacks += progress
            awaitCancellation()
        }
        val release = AppRelease("v3.0.0", 3_000_000, "", "https://github.com/apk")
        download.start(release); runCurrent()
        reportFromWorker(callbacks.single(), 10, 100)
        assertEquals(UpdateDownloadState.Downloading(), download.state.value)

        // The owner cancels before its queued worker progress can be applied.
        download.reset()
        assertEquals(UpdateDownloadState.Idle, download.state.value)
        runCurrent()
        assertEquals(UpdateDownloadState.Idle, download.state.value)

        download.start(release); runCurrent()
        reportFromWorker(callbacks.first(), 20, 100)
        runCurrent()
        assertEquals(UpdateDownloadState.Downloading(), download.state.value)
        reportFromWorker(callbacks.last(), 30, 200)
        runCurrent()
        assertEquals(UpdateDownloadState.Downloading(30, 200), download.state.value)
        download.reset(); runCurrent()
        assertEquals(UpdateDownloadState.Idle, download.state.value)
    }

    @Test fun queued_progress_cannot_replace_failure_or_ready_and_retry_and_reset_still_work() = runTest {
        val file = File.createTempFile("update-state-test", ".apk")
        var failDownload = true
        val download = AppUpdateDownload(backgroundScope) { _, progress ->
            reportFromWorker(progress, 4, 4)
            if (failDownload) throw IOException("Unavailable")
            file
        }
        val release = AppRelease("v3.0.0", 3_000_000, "", "https://github.com/apk")
        try {
            download.start(release); runCurrent()
            assertEquals(UpdateDownloadState.Failed("Unavailable"), download.state.value)
            failDownload = false
            download.start(release); runCurrent()
            assertEquals(UpdateDownloadState.Ready(file), download.state.value)
            assertTrue(file.exists())
            download.start(release)
            assertEquals(UpdateDownloadState.Ready(file), download.state.value)
            download.reset()
            assertEquals(UpdateDownloadState.Idle, download.state.value)
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test fun downloads_streamed_bytes_and_reports_progress() = runBlocking {
        respond("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nAPK!") { url, file ->
            var received = 0L
            var total = 0L
            call(url).downloadTo(file) { bytes, length -> received = bytes; total = length }
            assertEquals("APK!", file.readText())
            assertEquals(4L, received)
            assertEquals(4L, total)
        }
    }

    @Test fun http_errors_and_truncated_downloads_remove_partial_files() = runBlocking {
        for (response in listOf("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\nshort")) {
            respond(response) { url, file ->
                try {
                    call(url).downloadTo(file) { _, _ -> }
                    fail("Download should fail")
                } catch (_: IOException) {
                    assertFalse(file.exists())
                }
            }
        }
    }

    @Test fun cancelling_a_stalled_download_closes_the_call_and_removes_the_file() = runBlocking {
        ServerSocket(0).use { server ->
            val connected = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    connected.countDown()
                    finished.await(5, TimeUnit.SECONDS)
                }
            }
            val file = File.createTempFile("update-test", ".apk")
            val request = call("http://127.0.0.1:${server.localPort}/apk")
            try {
                val task = async { request.downloadTo(file) { _, _ -> } }
                withTimeout(5000) { while (connected.count > 0) delay(10) }
                task.cancel()
                task.join()
                assertTrue(request.isCanceled())
                withTimeout(5000) { while (file.exists()) delay(10) }
            } finally {
                finished.countDown()
                worker.join(1000)
                file.delete()
            }
        }
    }

    private fun reportFromWorker(progress: (Long, Long) -> Unit, received: Long, total: Long) {
        val worker = thread(isDaemon = true) { progress(received, total) }
        worker.join(5000)
        assertFalse("Progress callback did not return", worker.isAlive)
    }

    private fun call(url: String) = OkHttpClient().newCall(Request.Builder().url(url).build())

    private suspend fun respond(response: String, check: suspend (String, File) -> Unit) {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write(response.toByteArray())
                }
            }
            val file = File.createTempFile("update-test", ".apk")
            try {
                withTimeout(5000) { check("http://127.0.0.1:${server.localPort}/apk", file) }
            } finally {
                file.delete()
                worker.join(1000)
            }
        }
    }
}
