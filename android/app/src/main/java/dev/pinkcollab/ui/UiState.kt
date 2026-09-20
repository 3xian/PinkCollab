package dev.pinkcollab.ui

internal sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>
    data class Failed(val message: String) : LoadState<Nothing>
}

internal data class SessionKey(val hostId: String, val sessionId: String)

internal sealed interface OperationKey {
    data class Host(val hostId: String) : OperationKey
    data class Session(val key: SessionKey) : OperationKey
    data class CreateTask(val hostId: String, val cwd: String) : OperationKey
    data object PairHost : OperationKey
}
