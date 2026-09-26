package dev.pinkcollab.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/** Reads selected files and verifies the Gateway's upload acknowledgement. */
internal class AttachmentUploader(
    private val api: GatewayTransport,
    private val paired: (String) -> PairedHost,
) {
    suspend fun upload(context: Context, hostId: String, sessionId: String, fileId: String, name: String, uri: Uri) {
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 10 * 1024 * 1024) throw IOException("File exceeds the 10 MiB limit")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw IOException("Cannot open selected file")
        }
        require(bytes.isNotEmpty()) { "Empty files cannot be sent" }
        val host = paired(hostId)
        val response = try {
            api.upload(host.url, host.credential, "/api/v2/sessions/$sessionId/files/$fileId", name, bytes)
        } catch (error: GatewayHttpException) {
            if (error.statusCode == 404 && error.errorCode.isNullOrBlank()) throw IOException("Update the Gateway to send files", error)
            throw error
        }
        require(JSONObject(response).getString("fileId") == fileId) { "Gateway returned a different file ID" }
    }
}
