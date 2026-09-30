package dev.pinkcollab.ui

import dev.pinkcollab.data.UsageSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Quotas belong to a host, regardless of the selected session or runtime. */
internal class HostUsageLoader(
    private val scope: CoroutineScope,
    private val fetch: suspend (String) -> UsageSnapshot,
) {
    private val mutable = MutableStateFlow<Map<String, LoadState<UsageSnapshot>>>(emptyMap())
    val state = mutable.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    fun load(hostId: String) {
        if (jobs[hostId]?.isActive == true) return
        mutable.update { it + (hostId to LoadState.Loading) }
        jobs[hostId] = scope.launch {
            try {
                val snapshot = fetch(hostId)
                mutable.update { it + (hostId to LoadState.Ready(snapshot)) }
            }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                mutable.update { it + (hostId to LoadState.Failed(failure.message ?: "Unable to load usage")) }
            }
        }
    }
    fun removeHost(hostId: String) {
        jobs.remove(hostId)?.cancel()
        mutable.update { it - hostId }
    }
}
