package dev.pinkcollab.data

import dev.pinkcollab.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GatewayHttpException(val statusCode: Int, val errorCode: String?, message: String) : IOException(message)

class GatewayApi {
    // Tailscale Serve and similar reverse tunnels can reset large HTTP/2 timeline responses with
    // PROTOCOL_ERROR. PinkCollab makes few independent requests, so HTTP/1.1 is the reliable path.
    val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    fun validateURL(value: String): String {
        val url = value.trim().trimEnd('/').toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null) { "Enter the Gateway root URL" }
        require(url.isHttps || (BuildConfig.DEBUG && url.host in listOf("localhost", "127.0.0.1", "10.0.2.2"))) { "The Gateway must use HTTPS" }
        return url.toString().trimEnd('/')
    }
    suspend fun request(url: String, credential: String?, path: String, method: String = "GET", body: JSONObject? = null, query: Pair<String, String>? = null): String = suspendCancellableCoroutine { continuation ->
        val endpoint = (url + path).toHttpUrl().newBuilder().apply { query?.let { addQueryParameter(it.first, it.second) } }.build()
        val builder = Request.Builder().url(endpoint)
        credential?.let { builder.header("Authorization", "Bearer $it") }
        if (method != "GET") builder.method(method, body?.toString()?.toRequestBody("application/json".toMediaType()) ?: "{}".toRequestBody("application/json".toMediaType()))
        val call = client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        if (!continuation.isActive) return
                        if (it.isSuccessful) continuation.resume(text)
                        else {
                            val parsed = runCatching { JSONObject(text) }.getOrNull()
                            val message = if (it.code == 426) "Update PinkCollab to use this Gateway"
                                else parsed?.optString("message")?.ifBlank { parsed.optString("error") }?.ifBlank { "Gateway HTTP ${it.code}" }
                                    ?: "Gateway HTTP ${it.code}"
                            continuation.resumeWithException(GatewayHttpException(it.code, parsed?.optString("code"), message))
                        }
                    }
                } catch (error: Exception) {
                    // OkHttp does not route exceptions thrown while consuming a response body to
                    // onFailure. Resume the suspended caller instead of leaving its UI loading.
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}
