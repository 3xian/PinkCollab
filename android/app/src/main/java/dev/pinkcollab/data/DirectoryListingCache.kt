package dev.pinkcollab.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

internal data class DirectoryListingKey(val hostId: String, val path: String)

internal class DirectoryListingCache(
    private val scope: CoroutineScope,
    private val ttlMillis: Long = 30_000,
    private val maxEntries: Int = 128,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val listing: Listing, val expiresAt: Long)

    private val requests = ConcurrentHashMap<DirectoryListingKey, Deferred<Listing>>()
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
    private fun put(key: DirectoryListingKey, listing: Listing, generation: Long) {
        if (hostGenerations.getOrDefault(key.hostId, 0) != generation) return
        entries[key] = Entry(listing, now() + ttlMillis)
    }

    suspend fun getOrLoad(
        key: DirectoryListingKey,
        forceRefresh: Boolean = false,
        load: suspend () -> Listing,
    ): Listing {
        if (!forceRefresh) get(key)?.let { return it }

        val generation = synchronized(this) { hostGenerations.getOrDefault(key.hostId, 0) }
        val candidate = scope.async(start = CoroutineStart.LAZY) {
            load().also { put(key, it, generation) }
        }
        val active = requests.putIfAbsent(key, candidate) ?: candidate.also { request ->
            request.invokeOnCompletion { requests.remove(key, request) }
            request.start()
        }
        if (active !== candidate) candidate.cancel()
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
