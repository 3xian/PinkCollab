package dev.pinkcollab.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.pinkcollab.data.CredentialStore
import dev.pinkcollab.data.GatewayRepository
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class CollabViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    val repository = GatewayRepository(viewModelScope, CredentialStore(application))
    private val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            repository.reconnectUnavailableHosts()
        }

        override fun onLost(network: Network) {
            if (connectivity.activeNetwork == null) repository.networkUnavailable()
        }
    }

    private val mutableOperations = MutableStateFlow<Set<OperationKey>>(emptySet())
    internal val operations = mutableOperations.asStateFlow()
    private val operationLock = Any()
    private val draftStore = SessionDraftStore(decodeDrafts(
        savedStateHandle.get<Bundle>("sessionDraftsV2")?.getString("value")
            ?: savedStateHandle.get<String>("sessionDrafts"),
    ))
    internal val drafts = draftStore.state
    private val sessionCoordinator = SessionOperations(
        viewModelScope,
        RepositorySessionActions(application, repository),
        draftStore,
        ::clearDraftIfVersion,
        repository::error,
    )
    internal val sessionOperations = sessionCoordinator.operations
    private val mutableFileSelections = MutableStateFlow<Map<SessionKey, Int>>(emptyMap())
    internal val fileSelections = mutableFileSelections.asStateFlow()

    private val mutableDetailLoads = MutableStateFlow<Map<SessionKey, LoadState<Unit>>>(emptyMap())
    internal val detailLoads = mutableDetailLoads.asStateFlow()
    private val detailLoadLock = Any()
    private val detailJobs = mutableMapOf<SessionKey, Job>()
    private val detailVersions = ConcurrentHashMap<SessionKey, Long>()

    private val mutableModelLoads = MutableStateFlow<Map<SessionKey, LoadState<ModelCatalog>>>(emptyMap())
    internal val modelLoads = mutableModelLoads.asStateFlow()
    private val modelLoadLock = Any()

    init {
        savedStateHandle.remove<String>("sessionDrafts")
        savedStateHandle.setSavedStateProvider("sessionDraftsV2") {
            Bundle().apply { putString("value", encodeDrafts(draftStore.state.value)) }
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onCleared() {
        connectivity.unregisterNetworkCallback(networkCallback)
        super.onCleared()
    }

    internal fun run(
        key: OperationKey,
        errorMessage: String = "Action failed",
        onError: ((String) -> Unit)? = null,
        action: suspend () -> Unit,
    ) {
        val started = synchronized(operationLock) {
            if (key in mutableOperations.value) {
                false
            } else {
                mutableOperations.value += key
                true
            }
        }
        if (!started) return
        viewModelScope.launch {
            if (onError == null) repository.error(null)
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: errorMessage
                if (onError == null) repository.error(message) else onError(message)
            } finally {
                synchronized(operationLock) { mutableOperations.value -= key }
            }
        }
    }

    internal fun sendPrompt(session: Session) = sessionCoordinator.send(session)
    internal fun sessionCommand(session: Session, command: SessionUserCommand) = sessionCoordinator.command(session, command)
    internal fun respond(session: Session, body: org.json.JSONObject) = sessionCoordinator.respond(session, body)
    internal fun selectModel(session: Session, model: ModelInfo) = sessionCoordinator.selectModel(session, model)
    internal fun setThinkingLevel(session: Session, level: String) = sessionCoordinator.setThinkingLevel(session, level)
    internal fun loadSavedHistory(session: Session) = sessionCoordinator.loadSavedHistory(session)
    internal fun loadEarlierHistory(session: Session) = sessionCoordinator.loadEarlierHistory(session)

    internal fun setDraftText(key: SessionKey, text: String) = draftStore.setText(key, text)
    internal fun selectDraftFile(key: SessionKey, uri: Uri) {
        mutableFileSelections.update { it + (key to (it[key] ?: 0) + 1) }
        viewModelScope.launch {
            try {
                val file = selectedFile(getApplication<Application>(), uri)
                if (key.hostId in repository.state.value.hosts) addDraftFile(key, file)
                else releaseUnusedUris(setOf(file.uri))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                repository.error(error.message ?: "Cannot read selected file")
            } finally {
                mutableFileSelections.update { current ->
                    val remaining = (current[key] ?: 1) - 1
                    if (remaining > 0) current + (key to remaining) else current - key
                }
            }
        }
    }

    internal fun addDraftFile(key: SessionKey, file: SelectedFile) {
        draftStore.addFile(key, file)
        releaseUnusedUris(setOf(file.uri))
    }

    internal fun removeDraftFile(key: SessionKey, fileId: String) {
        val before = trackedUris()
        draftStore.removeFile(key, fileId)
        releaseUnusedUris(before)
    }

    internal fun clearDraftIfVersion(key: SessionKey, version: Long) {
        val before = trackedUris()
        draftStore.clearIfVersion(key, version)
        releaseUnusedUris(before)
    }

    internal fun removeHostDrafts(hostId: String) {
        val before = trackedUris()
        draftStore.removeHost(hostId)
        releaseUnusedUris(before)
    }

    private fun trackedUris(): Set<String> = draftStore.state.value.values.flatMap { draft -> draft.files.map { it.uri } }.toSet()

    private fun releaseUnusedUris(before: Set<String>) {
        val unused = before - trackedUris()
        if (unused.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            unused.forEach { uri ->
                if (uri !in trackedUris()) {
                    runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
            }
        }
    }

    internal fun loadDetail(session: Session, force: Boolean = false) {
        val key = SessionKey(session.hostId, session.id)
        val version = synchronized(detailLoadLock) {
            if (!force && (repository.state.value.details.containsKey(session.id) || mutableDetailLoads.value[key] == LoadState.Loading)) {
                null
            } else {
                mutableDetailLoads.value += key to LoadState.Loading
                ((detailVersions[key] ?: 0L) + 1L).also { detailVersions[key] = it }
            }
        }
        if (version == null) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                repository.detail(session.hostId, session.id)
                if (detailVersions[key] == version) mutableDetailLoads.update { it + (key to LoadState.Ready(Unit)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Unable to load session"
                if (detailVersions[key] == version) {
                    mutableDetailLoads.update { it + (key to LoadState.Failed(message)) }
                    repository.error(message)
                }
            }
        }
        synchronized(detailLoadLock) {
            if (detailVersions[key] == version) detailJobs.put(key, job)?.cancel() else job.cancel()
        }
        job.invokeOnCompletion { synchronized(detailLoadLock) { if (detailJobs[key] === job) detailJobs.remove(key) } }
        job.start()
    }

    internal fun loadModels(session: Session, force: Boolean = true) {
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
        viewModelScope.launch {
            try {
                val models = repository.models(session.hostId, session.id)
                mutableModelLoads.update { it + (key to LoadState.Ready(models)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableModelLoads.update {
                    it + (key to LoadState.Failed(e.message ?: "Unable to load models"))
                }
            }
        }
    }
}
