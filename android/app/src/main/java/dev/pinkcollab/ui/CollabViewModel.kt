package dev.pinkcollab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pinkcollab.data.CredentialStore
import dev.pinkcollab.data.GatewayRepository
import dev.pinkcollab.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CollabViewModel(application: Application) : AndroidViewModel(application) {
    val repository = GatewayRepository(viewModelScope, CredentialStore(application))

    private val mutableOperations = MutableStateFlow<Set<OperationKey>>(emptySet())
    internal val operations = mutableOperations.asStateFlow()
    private val operationLock = Any()

    private val mutableDetailLoads = MutableStateFlow<Map<SessionKey, LoadState<Unit>>>(emptyMap())
    internal val detailLoads = mutableDetailLoads.asStateFlow()
    private val detailLoadLock = Any()

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

    internal fun loadDetail(session: Session, force: Boolean = false) {
        val key = SessionKey(session.hostId, session.id)
        val shouldLoad = synchronized(detailLoadLock) {
            if (!force && (repository.state.value.details.containsKey(session.id) || mutableDetailLoads.value[key] == LoadState.Loading)) {
                false
            } else {
                mutableDetailLoads.value += key to LoadState.Loading
                true
            }
        }
        if (!shouldLoad) return
        viewModelScope.launch {
            try {
                repository.detail(session.hostId, session.id)
                mutableDetailLoads.update { it + (key to LoadState.Ready(Unit)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Unable to load task"
                mutableDetailLoads.update { it + (key to LoadState.Failed(message)) }
                repository.error(message)
            }
        }
    }
}
