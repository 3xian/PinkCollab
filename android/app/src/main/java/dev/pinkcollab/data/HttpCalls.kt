package dev.pinkcollab.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun Call.awaitSuccessfulBody(errorForStatus: (Int, String) -> IOException): String =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        if (!continuation.isActive) return
                        if (it.isSuccessful) continuation.resume(text)
                        else continuation.resumeWithException(errorForStatus(it.code, text))
                    }
                } catch (error: Exception) {
                    // OkHttp does not deliver errors from consuming a response body to onFailure.
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
