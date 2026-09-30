package dev.pinkcollab.data

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class SocketFramesTest {
    private fun gzip(text: String) = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(text.toByteArray()) }
    }.toByteArray().toByteString()

    @Test fun negotiated_gzip_preserves_unicode_json() {
        val text = """{"type":"host_snapshot","name":"本机 🐱"}"""
        assertEquals(text, decodeSocketFrame(gzip(text), true))
    }

    @Test(expected = IOException::class)
    fun binary_frames_without_negotiation_are_rejected() {
        decodeSocketFrame(gzip("{}"), false)
    }

    @Test(expected = IOException::class)
    fun corrupt_gzip_is_rejected() {
        decodeSocketFrame(byteArrayOf(1, 2, 3).toByteString(), true)
    }

    @Test(expected = IOException::class)
    fun compressed_frames_cannot_expand_past_the_budget() {
        decodeSocketFrame(gzip("x".repeat(8 * 1024 * 1024 + 1)), true)
    }
}
