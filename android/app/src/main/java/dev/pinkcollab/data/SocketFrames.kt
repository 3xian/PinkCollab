package dev.pinkcollab.data

import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

internal const val GzipSocketProtocol = "pinkcollab.v3.gzip"
internal const val InitialHistoryPageSize = 25
private const val MaxDecodedFrameBytes = 8 * 1024 * 1024

/** Binary frames are permitted only after gzip subprotocol negotiation. */
internal fun decodeSocketFrame(bytes: ByteString, gzipNegotiated: Boolean): String {
    if (!gzipNegotiated) throw IOException("Unnegotiated binary frame")
    return GZIPInputStream(bytes.toByteArray().inputStream()).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MaxDecodedFrameBytes) throw IOException("Frame exceeds decode budget")
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }
}
