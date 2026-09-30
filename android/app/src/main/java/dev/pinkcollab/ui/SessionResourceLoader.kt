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
    private val modelGenerations = mutableMapOf<SessionKey, String?>()
    private var nextModelVersion = 0L

    fun loadDetail(session: Session, force: Boolean = false) {
        val key = SessionKey(session.hostId, session.id)
        val version = synchronized(detailLoadLock) {
            // The Gateway focuses one session at a time. Drop the previous focus's
            // request and error before it can time out or reappear on a later visit.
            detailVersions.keys.retainAll(setOf(key))
            detailJobs.keys.toList().filter { it != key }.forEach { detailJobs.remove(it)?.cancel() }
            mutableDetailLoads.value = mutableDetailLoads.value.filterKeys { it == key }
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

    fun loadModels(session: Session, force: Boolean = false) {
        val key = SessionKey(session.hostId, session.id)
        val request = synchronized(modelLoadLock) {
            val current = mutableModelLoads.value[key]
            val sameGeneration = key in modelGenerations && modelGenerations[key] == session.generation
            if (((current == LoadState.Loading || (current is LoadState.Ready && current.refreshing)) && sameGeneration) ||
                (!force && current is LoadState.Ready && sameGeneration)) {
                null
            } else {
                val cached = (current as? LoadState.Ready)?.takeIf { sameGeneration }
                mutableModelLoads.value += key to (cached?.copy(refreshing = true) ?: LoadState.Loading)
                modelGenerations[key] = session.generation
                val version = (++nextModelVersion).also { modelVersions[key] = it }
                version to cached
            }
        }
        if (request == null) return
        val (version, cached) = request
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val models = actions.models(session)
                synchronized(modelLoadLock) {
                    if (modelVersions[key] == version) mutableModelLoads.value += key to LoadState.Ready(models)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                val message = failure.message ?: "Unable to load models"
                val current = synchronized(modelLoadLock) {
                    if (modelVersions[key] != version) false else {
                        mutableModelLoads.value += key to (cached ?: LoadState.Failed(message))
                        true
                    }
                }
                if (current && cached != null) reportError(message)
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
            modelGenerations.keys.removeAll { it.hostId == hostId }
            mutableModelLoads.value = mutableModelLoads.value.filterKeys { it.hostId != hostId }
            modelJobs.filterKeys { it.hostId == hostId }.values.toList().also {
                modelJobs.keys.removeAll { key -> key.hostId == hostId }
            }
        }
        (detailToCancel + modelToCancel).forEach(Job::cancel)
    }
}
