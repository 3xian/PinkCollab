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
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.AttentionResponse
import dev.pinkcollab.data.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CollabViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = GatewayRepository(viewModelScope, CredentialStore(application), application)
    internal val appState = repository.state
    private val effectChannel = Channel<UiEffect>(Channel.BUFFERED)
    internal val effects = effectChannel.receiveAsFlow()
    private fun showError(message: String) { effectChannel.trySend(UiEffect.ShowSnackbar(message)) }
    private val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            repository.reconnectUnavailableHosts()
        }

        override fun onLost(network: Network) {
            if (connectivity.activeNetwork == null) repository.networkUnavailable()
        }
    }

    private val draftStore = SessionDraftStore(decodeDrafts(
        savedStateHandle.get<Bundle>("sessionDraftsV2")?.getString("value")
            ?: savedStateHandle.get<String>("sessionDrafts"),
    ))
    internal val drafts = draftStore.state
    private val hostOperations = HostOperations(
        viewModelScope,
        RepositoryHostActions(repository),
        { effectChannel.trySend(it) },
        { hostId -> sessionCoordinator.cancelHost(hostId) },
        { hostId ->
            removeHostDrafts(hostId)
            resourceLoader.removeHost(hostId)
            mutableFileSelections.update { it.filterKeys { key -> key.hostId != hostId } }
        },
        { session ->
            loadDetail(session)
            sessionCoordinator.command(session, SessionUserCommand.Start)
        },
    )
    internal val operations = hostOperations.operations
    internal val directory = hostOperations.directory
    private val sessionCoordinator = SessionOperations(
        viewModelScope,
        RepositorySessionActions(application, repository),
        draftStore,
        ::clearDraftIfVersion,
        ::showError,
    )
    internal val sessionOperations = sessionCoordinator.operations
    internal val sendProgress = sessionCoordinator.sendProgress
    private val mutableFileSelections = MutableStateFlow<Map<SessionKey, Int>>(emptyMap())
    internal val fileSelections = mutableFileSelections.asStateFlow()

    private val resourceLoader = SessionResourceLoader(viewModelScope, object : SessionResourceActions {
        override fun hasDetail(key: SessionKey) = key in repository.state.value.details
        override suspend fun detail(session: Session) = repository.detail(session.hostId, session.id)
        override suspend fun models(session: Session) = repository.models(session.hostId, session.id)
    }, ::showError)
    internal val detailLoads = resourceLoader.detailLoads
    internal val modelLoads = resourceLoader.modelLoads

    init {
        savedStateHandle.remove<String>("sessionDrafts")
        savedStateHandle.setSavedStateProvider("sessionDraftsV2") {
            Bundle().apply { putString("value", encodeDrafts(draftStore.state.value)) }
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        viewModelScope.launch { repository.errors.collect(::showError) }
    }

    override fun onCleared() {
        connectivity.unregisterNetworkCallback(networkCallback)
        super.onCleared()
    }

    internal fun pairHost(url: String, token: String, attemptId: Long) = hostOperations.pair(url, token, attemptId)
    internal fun refreshHost(hostId: String) = hostOperations.refresh(
        hostId, repository.state.value.hosts[hostId]?.connection is ConnectionState.Online,
    )
    internal fun forgetHost(hostId: String) = hostOperations.forget(hostId)
    internal fun reconnectUnavailableHosts() = hostOperations.reconnectUnavailableHosts()
    internal fun loadDirectory(key: BrowserKey, forceRefresh: Boolean = false) = hostOperations.loadDirectory(key, forceRefresh)
    internal fun createSession(hostId: String, path: String) = hostOperations.create(hostId, path)

    internal fun sendPrompt(session: Session) = sessionCoordinator.send(session)
    internal fun sessionCommand(session: Session, command: SessionUserCommand) = sessionCoordinator.command(session, command)
    internal fun respond(session: Session, response: AttentionResponse) = sessionCoordinator.respond(session, response)
    internal fun selectModel(session: Session, model: ModelInfo) = sessionCoordinator.selectModel(session, model)
    internal fun setThinkingLevel(session: Session, level: String) = sessionCoordinator.setThinkingLevel(session, level)
    internal fun loadSavedHistory(session: Session) = sessionCoordinator.loadSavedHistory(session)
    internal fun loadEarlierHistory(session: Session) = sessionCoordinator.loadEarlierHistory(session)

    internal fun onSessionAction(session: Session, action: SessionAction) {
        val key = SessionKey(session.hostId, session.id)
        when (action) {
            SessionAction.Retry -> loadDetail(session, force = true)
            SessionAction.Send -> sendPrompt(session)
            is SessionAction.DraftChanged -> setDraftText(key, action.text)
            is SessionAction.FileSelected -> selectDraftFile(key, action.uri)
            is SessionAction.FileRemoved -> removeDraftFile(key, action.id)
            is SessionAction.Command -> sessionCommand(session, action.command)
            is SessionAction.Respond -> respond(session, action.response)
            is SessionAction.LoadModels -> loadModels(session, action.force)
            is SessionAction.SelectModel -> selectModel(session, action.model)
            is SessionAction.SetThinkingLevel -> setThinkingLevel(session, action.level)
            SessionAction.LoadSavedHistory -> loadSavedHistory(session)
            SessionAction.LoadEarlierHistory -> loadEarlierHistory(session)
        }
    }

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
                showError(error.message ?: "Cannot read selected file")
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

    internal fun loadDetail(session: Session, force: Boolean = false) = resourceLoader.loadDetail(session, force)
    internal fun loadModels(session: Session, force: Boolean = true) = resourceLoader.loadModels(session, force)
}
