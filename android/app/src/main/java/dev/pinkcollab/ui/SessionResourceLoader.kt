package dev.pinkcollab.ui

import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal interface SessionResourceActions {
    fun hasDetail(key: SessionKey): Boolean
    suspend fun detail(session: Session)
    suspend fun models(session: Session): ModelCatalog
}

/** Keeps detail and model loading state separate from Gateway state and fences stale detail jobs. */
internal class SessionResourceLoader(
    private val scope: CoroutineScope,
    private val actions: SessionResourceActions,
    private val reportError: (String) -> Unit,
) {
    private val mutableDetailLoads = MutableStateFlow<Map<SessionKey, LoadState<Unit>>>(emptyMap())
    val detailLoads = mutableDetailLoads.asStateFlow()
    private val detailLoadLock = Any()
    private val detailJobs = mutableMapOf<SessionKey, Job>()
    private val detailVersions = ConcurrentHashMap<SessionKey, Long>()

    private val mutableModelLoads = MutableStateFlow<Map<SessionKey, LoadState<ModelCatalog>>>(emptyMap())
    val modelLoads = mutableModelLoads.asStateFlow()
    private val modelLoadLock = Any()

    fun loadDetail(session: Session, force: Boolean = false) {
        val key = SessionKey(session.hostId, session.id)
        val version = synchronized(detailLoadLock) {
            if (!force && (actions.hasDetail(key) || mutableDetailLoads.value[key] == LoadState.Loading)) {
                null
            } else {
                mutableDetailLoads.value += key to LoadState.Loading
                ((detailVersions[key] ?: 0L) + 1L).also { detailVersions[key] = it }
            }
        }
        if (version == null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                actions.detail(session)
                if (detailVersions[key] == version) mutableDetailLoads.update { it + (key to LoadState.Ready(Unit)) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                val message = failure.message ?: "Unable to load session"
                if (detailVersions[key] == version) {
                    mutableDetailLoads.update { it + (key to LoadState.Failed(message)) }
                    reportError(message)
                }
            }
        }
        synchronized(detailLoadLock) {
            if (detailVersions[key] == version) detailJobs.put(key, job)?.cancel() else job.cancel()
        }
        job.invokeOnCompletion { synchronized(detailLoadLock) { if (detailJobs[key] === job) detailJobs.remove(key) } }
        job.start()
    }

    fun loadModels(session: Session, force: Boolean = true) {
        val key = SessionKey(session.hostId, session.id)
        val shouldLoad = synchronized(modelLoadLock) {
            val current = mutableModelLoads.value[key]
            if (current == LoadState.Loading || (!force && current is LoadState.Ready)) {
                false
            } else {
                mutableModelLoads.value += key to LoadState.Loading
                true
            }
        }
        if (!shouldLoad) return
        scope.launch {
            try {
                val models = actions.models(session)
                mutableModelLoads.update { it + (key to LoadState.Ready(models)) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mutableModelLoads.update {
                    it + (key to LoadState.Failed(failure.message ?: "Unable to load models"))
                }
            }
        }
    }
}
