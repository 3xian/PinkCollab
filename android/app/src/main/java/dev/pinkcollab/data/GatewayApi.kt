package dev.pinkcollab.data

import dev.pinkcollab.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GatewayApi {
    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    fun validateURL(value: String): String {
        val url = value.trim().trimEnd('/').toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null) { "请填写 Gateway 根地址" }
        require(url.isHttps || (BuildConfig.DEBUG && url.host in listOf("localhost", "127.0.0.1", "10.0.2.2"))) { "Gateway 必须使用 HTTPS" }
        return url.toString().trimEnd('/')
    }
    suspend fun request(url: String, credential: String?, path: String, method: String = "GET", body: JSONObject? = null, query: Pair<String, String>? = null): String = withContext(Dispatchers.IO) {
        val endpoint = (url + path).toHttpUrl().newBuilder().apply { query?.let { addQueryParameter(it.first, it.second) } }.build()
        val builder = Request.Builder().url(endpoint)
        credential?.let { builder.header("Authorization", "Bearer $it") }
        if (method != "GET") builder.method(method, body?.toString()?.toRequestBody("application/json".toMediaType()) ?: "{}".toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(runCatching { JSONObject(text).getString("error") }.getOrDefault("Gateway HTTP ${response.code}"))
            text
        }
    }
}
