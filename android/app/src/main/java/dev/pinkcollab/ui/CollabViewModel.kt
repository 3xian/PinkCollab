package dev.pinkcollab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pinkcollab.data.CredentialStore
import dev.pinkcollab.data.GatewayRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class CollabViewModel(application: Application) : AndroidViewModel(application) {
    val repository = GatewayRepository(viewModelScope, CredentialStore(application))
    fun run(action: suspend () -> Unit) {
        if (repository.state.value.loading) return
        viewModelScope.launch {
            repository.error(null); repository.loading(true)
            try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { repository.error(e.message ?: "Action failed") }
            finally { repository.loading(false) }
        }
    }
}
