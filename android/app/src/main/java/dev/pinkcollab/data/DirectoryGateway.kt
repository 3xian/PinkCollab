package dev.pinkcollab.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Owns directory cache and speculative child listing requests. */
internal class DirectoryGateway(
    private val scope: CoroutineScope,
    private val api: GatewayTransport,
    private val paired: (String) -> PairedHost,
) {
    private val listings = DirectoryListingCache(scope)

    suspend fun listing(hostId: String, path: String, forceRefresh: Boolean = false): Listing =
        listings.getOrLoad(DirectoryListingKey(hostId, path), forceRefresh) {
            val host = paired(hostId)
            val response = api.request(host.url, host.credential, "/api/v4/fs/list", query = "path" to path)
            withContext(Dispatchers.Default) { JSONObject(response).listing() }
        }

    fun prefetch(hostId: String, paths: List<String>) {
        paths.distinct().take(12).forEach { path -> scope.launch { runCatching { listing(hostId, path) } } }
    }

    fun removeHost(hostId: String) = listings.removeHost(hostId)
}
