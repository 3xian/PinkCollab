package dev.pinkcollab.data

import android.content.Context
import android.content.pm.PackageManager
import dev.pinkcollab.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

private const val MaxApkBytes = 150 * 1024 * 1024L

internal class AppUpdateDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .followSslRedirects(false)
        .build()

    suspend fun download(release: AppRelease, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val url = release.apkUrl?.toHttpUrlOrNull()?.takeIf { it.isHttps }
            ?: throw IOException("This release has no APK available. Try checking again later.")
        val directory = File(context.cacheDir, "updates")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create update storage")
        val file = File.createTempFile("update-", ".apk", directory)
        try {
            client.newCall(Request.Builder().url(url).build()).downloadTo(file, progress)
            validateApk(file, release)
            file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, release: AppRelease) {
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
            ?: throw IOException("The downloaded file is not a valid APK")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        if (archive.packageName != context.packageName || archive.versionCode != release.versionCode ||
            archive.versionCode <= BuildConfig.VERSION_CODE) {
            throw IOException("The downloaded APK does not match this update")
        }
        val expected = installed.signatures?.toSet().orEmpty()
        if (expected.isEmpty() || archive.signatures?.toSet() != expected) {
            throw IOException("The APK signature does not match the installed app")
        }
    }
}

internal suspend fun Call.downloadTo(file: File, progress: (Long, Long) -> Unit): File =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                file.delete()
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw IOException("Download failed (HTTP ${it.code})")
                        val body = it.body ?: throw IOException("Empty APK response")
                        val total = body.contentLength()
                        if (total > MaxApkBytes) throw IOException("APK is too large")
                        var received = 0L
                        var lastPercent = -1L
                        body.byteStream().use { input ->
                            file.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("Download cancelled")
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    received += count
                                    if (received > MaxApkBytes) throw IOException("APK is too large")
                                    output.write(buffer, 0, count)
                                    val percent = if (total > 0) received * 100 / total else received / (1024 * 1024)
                                    if (percent != lastPercent) {
                                        progress(received, total)
                                        lastPercent = percent
                                    }
                                }
                            }
                        }
                        if (received == 0L || (total >= 0 && received != total)) throw IOException("Incomplete APK download")
                    }
                    continuation.resume(file, onCancellation = { _, value, _ -> value.delete() })
                } catch (error: Exception) {
                    file.delete()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
