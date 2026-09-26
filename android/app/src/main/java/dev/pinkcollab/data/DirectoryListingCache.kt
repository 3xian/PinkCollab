package dev.pinkcollab.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

internal data class DirectoryListingKey(val hostId: String, val path: String)

internal class DirectoryListingCache(
    private val scope: CoroutineScope,
    private val ttlMillis: Long = 30_000,
    private val maxEntries: Int = 128,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val listing: Listing, val expiresAt: Long)

    private val requests = mutableMapOf<DirectoryListingKey, Deferred<Listing>>()
    private val hostGenerations = mutableMapOf<String, Long>()
    private val entries = object : LinkedHashMap<DirectoryListingKey, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DirectoryListingKey, Entry>) =
            size > maxEntries
    }

    @Synchronized
    fun get(key: DirectoryListingKey): Listing? {
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= now()) {
            entries.remove(key)
            return null
        }
        return entry.listing
    }

    @Synchronized
    private fun putIfCurrent(key: DirectoryListingKey, listing: Listing, generation: Long, request: Deferred<Listing>) {
        if (hostGenerations.getOrDefault(key.hostId, 0) != generation || requests[key] !== request) return
        entries[key] = Entry(listing, now() + ttlMillis)
    }

    suspend fun getOrLoad(
        key: DirectoryListingKey,
        forceRefresh: Boolean = false,
        load: suspend () -> Listing,
    ): Listing {
        val (active, replaced) = synchronized(this) {
            if (!forceRefresh) get(key)?.let { return it }
            val inFlight = requests[key]
            if (inFlight != null && !forceRefresh) return@synchronized inFlight to null

            val generation = hostGenerations.getOrDefault(key.hostId, 0)
            lateinit var candidate: Deferred<Listing>
            candidate = scope.async(start = CoroutineStart.LAZY) {
                load().also { putIfCurrent(key, it, generation, candidate) }
            }
            requests[key] = candidate
            candidate.invokeOnCompletion {
                synchronized(this) {
                    if (requests[key] === candidate) requests.remove(key)
                }
            }
            candidate.start()
            candidate to inFlight
        }
        replaced?.cancel()
        return active.await()
    }

    @Synchronized
    fun removeHost(hostId: String) {
        hostGenerations[hostId] = hostGenerations.getOrDefault(hostId, 0) + 1
        requests.entries
            .filter { it.key.hostId == hostId }
            .forEach { (key, request) ->
                if (requests.remove(key, request)) request.cancel()
            }
        entries.keys.removeAll { it.hostId == hostId }
    }
}
