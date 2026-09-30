package dev.pinkcollab.data

import android.content.Context
import dev.pinkcollab.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class AppRelease(
    val tag: String,
    val versionCode: Int,
    val notes: String,
    val updateUrl: String,
    val apkUrl: String? = updateUrl,
)

private val releaseTag = Regex("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
private const val MaxAndroidVersionCode = 2_100_000_000L
private const val AutoCheckIntervalMillis = 24 * 60 * 60 * 1000L

internal fun versionCodeFromTag(tag: String): Int? {
    val match = releaseTag.matchEntire(tag) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    val patch = match.groupValues[3].toIntOrNull() ?: return null
    if (minor > 999 || patch > 999 || major > MaxAndroidVersionCode / 1_000_000) return null
    val code = major * 1_000_000L + minor * 1_000L + patch
    return code.takeIf { it <= MaxAndroidVersionCode }?.toInt()
}

internal fun isNewerRelease(release: AppRelease, currentVersionCode: Int): Boolean =
    release.versionCode > currentVersionCode

private fun httpsUrl(value: String): String? = value.takeIf { it.toHttpUrlOrNull()?.isHttps == true }

internal fun parseAppRelease(json: String): AppRelease? = runCatching {
    val release = JSONObject(json)
    val tag = release.optString("tag_name")
    val code = versionCodeFromTag(tag) ?: return null
    val releaseUrl = httpsUrl(release.optString("html_url"))
    val assets = release.optJSONArray("assets")
    val apkUrl = (0 until (assets?.length() ?: 0)).asSequence()
        .mapNotNull { assets?.optJSONObject(it) }
        .filter { it.optString("name") == "pinkcollab-android.apk" }
        .mapNotNull { httpsUrl(it.optString("browser_download_url")) }
        .firstOrNull()
    AppRelease(
        tag = tag,
        versionCode = code,
        notes = if (release.isNull("body")) "" else release.optString("body"),
        updateUrl = apkUrl ?: releaseUrl ?: return null,
        apkUrl = apkUrl,
    )
}.getOrNull()

internal class AppUpdateChecker(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun checkAutomatically(): AppRelease? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val previous = preferences.getLong("last-auto-request", 0L)
        if (previous > 0 && now - previous < AutoCheckIntervalMillis) return@withContext null
        // Count attempts, including failed requests, so an unavailable GitHub cannot delay startup repeatedly.
        if (!preferences.edit().putLong("last-auto-request", now).commit()) return@withContext null
        fetchLatest()
    }

    suspend fun fetchLatest(): AppRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/3xian/PinkCollab/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PinkCollab-Android/${BuildConfig.VERSION_NAME}")
            .build()
        val json = client.newCall(request).awaitSuccessfulBody { code, _ -> IOException("GitHub HTTP $code") }
        parseAppRelease(json) ?: throw IOException("Invalid GitHub release")
    }
}
