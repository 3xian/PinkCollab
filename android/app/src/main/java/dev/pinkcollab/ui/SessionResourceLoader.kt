package dev.pinkcollab.ui

import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val detailVersions = mutableMapOf<SessionKey, Long>()
    private var nextDetailVersion = 0L

    private val mutableModelLoads = MutableStateFlow<Map<SessionKey, LoadState<ModelCatalog>>>(emptyMap())
    val modelLoads = mutableModelLoads.asStateFlow()
    private val modelLoadLock = Any()
    private val modelJobs = mutableMapOf<SessionKey, Job>()
    private val modelVersions = mutableMapOf<SessionKey, Long>()
    private var nextModelVersion = 0L

    fun loadDetail(session: Session, force: Boolean = false) {
        val key = SessionKey(session.hostId, session.id)
        val version = synchronized(detailLoadLock) {
            if (!force && (actions.hasDetail(key) || mutableDetailLoads.value[key] == LoadState.Loading)) {
                null
            } else {
                mutableDetailLoads.value += key to LoadState.Loading
                (++nextDetailVersion).also { detailVersions[key] = it }
            }
        }
        if (version == null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                actions.detail(session)
                synchronized(detailLoadLock) {
                    if (detailVersions[key] == version) mutableDetailLoads.value += key to LoadState.Ready(Unit)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                val message = failure.message ?: "Unable to load session"
                val current = synchronized(detailLoadLock) {
                    if (detailVersions[key] != version) false else {
                        mutableDetailLoads.value += key to LoadState.Failed(message)
                        true
                    }
                }
                if (current) reportError(message)
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
        val version = synchronized(modelLoadLock) {
            val current = mutableModelLoads.value[key]
            if (current == LoadState.Loading || (!force && current is LoadState.Ready)) {
                null
            } else {
                mutableModelLoads.value += key to LoadState.Loading
                (++nextModelVersion).also { modelVersions[key] = it }
            }
        }
        if (version == null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val models = actions.models(session)
                synchronized(modelLoadLock) {
                    if (modelVersions[key] == version) mutableModelLoads.value += key to LoadState.Ready(models)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                synchronized(modelLoadLock) {
                    if (modelVersions[key] == version) {
                        mutableModelLoads.value += key to LoadState.Failed(failure.message ?: "Unable to load models")
                    }
                }
            }
        }
        synchronized(modelLoadLock) {
            if (modelVersions[key] == version) modelJobs.put(key, job)?.cancel() else job.cancel()
        }
        job.invokeOnCompletion { synchronized(modelLoadLock) { if (modelJobs[key] === job) modelJobs.remove(key) } }
        job.start()
    }

    fun removeHost(hostId: String) {
        val detailToCancel = synchronized(detailLoadLock) {
            detailVersions.keys.removeAll { it.hostId == hostId }
            mutableDetailLoads.value = mutableDetailLoads.value.filterKeys { it.hostId != hostId }
            detailJobs.filterKeys { it.hostId == hostId }.values.toList().also {
                detailJobs.keys.removeAll { key -> key.hostId == hostId }
            }
        }
        val modelToCancel = synchronized(modelLoadLock) {
            modelVersions.keys.removeAll { it.hostId == hostId }
            mutableModelLoads.value = mutableModelLoads.value.filterKeys { it.hostId != hostId }
            modelJobs.filterKeys { it.hostId == hostId }.values.toList().also {
                modelJobs.keys.removeAll { key -> key.hostId == hostId }
            }
        }
        (detailToCancel + modelToCancel).forEach(Job::cancel)
    }
}
