package dev.pinkcollab.ui

import dev.pinkcollab.data.AppRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

internal sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val received: Long = 0, val total: Long = -1) : UpdateDownloadState
    data class Ready(val file: File) : UpdateDownloadState
    data class Failed(val message: String) : UpdateDownloadState
}

internal class AppUpdateDownload(
    private val scope: CoroutineScope,
    private val download: suspend (AppRelease, (Long, Long) -> Unit) -> File,
) {
    private val mutable = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    // State and attempt ownership stay on the scope's dispatcher, including worker progress.
    private var generation = 0L

    fun start(release: AppRelease) {
        if (job?.isActive == true || mutable.value is UpdateDownloadState.Ready) return
        val attempt = ++generation
        mutable.value = UpdateDownloadState.Downloading()
        job = scope.launch {
            try {
                val file = download(release) { received, total ->
                    scope.launch {
                        if (generation == attempt && mutable.value is UpdateDownloadState.Downloading) {
                            mutable.value = UpdateDownloadState.Downloading(received, total)
                        }
                    }
                }
                mutable.value = UpdateDownloadState.Ready(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.value = UpdateDownloadState.Failed(error.message ?: "Could not download the update")
            }
        }
    }

    fun reset() {
        generation++
        job?.cancel()
        (mutable.value as? UpdateDownloadState.Ready)?.file?.delete()
        mutable.value = UpdateDownloadState.Idle
    }
}
