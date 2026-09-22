package dev.pinkcollab.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectoryListingCacheTest {
    private val listing = Listing("F:/code", null, emptyList())

    @Test
    fun returnsFreshEntryAndExpiresIt() = runTest {
        var now = 100L
        val cache = DirectoryListingCache(backgroundScope, ttlMillis = 50, now = { now })
        val key = DirectoryListingKey("host", listing.path)

        assertEquals(listing, cache.getOrLoad(key) { listing })
        assertEquals(listing, cache.getOrLoad(key) { error("fresh entries must not reload") })

        now = 150L
        val refreshed = listing.copy(path = "F:/refreshed")
        assertEquals(refreshed, cache.getOrLoad(key) { refreshed })
    }

    @Test
    fun evictsLeastRecentlyUsedEntry() = runTest {
        val cache = DirectoryListingCache(backgroundScope, maxEntries = 2)
        val first = DirectoryListingKey("host", "first")
        val second = DirectoryListingKey("host", "second")
        val third = DirectoryListingKey("host", "third")

        cache.getOrLoad(first) { listing }
        cache.getOrLoad(second) { listing }
        cache.get(first)
        cache.getOrLoad(third) { listing }

        assertEquals(listing, cache.get(first))
        assertNull(cache.get(second))
        assertEquals(listing, cache.get(third))
    }

    @Test
    fun removesOnlyRequestedHost() = runTest {
        val cache = DirectoryListingCache(backgroundScope)
        val removed = DirectoryListingKey("removed", "path")
        val retained = DirectoryListingKey("retained", "path")
        cache.getOrLoad(removed) { listing }
        cache.getOrLoad(retained) { listing }

        cache.removeHost("removed")

        assertNull(cache.get(removed))
        assertEquals(listing, cache.get(retained))
    }

    @Test
    fun cancelledWaiterDoesNotBreakInFlightDeduplication() = runTest {
        val cache = DirectoryListingCache(backgroundScope)
        val key = DirectoryListingKey("host", "path")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val load: suspend () -> Listing = {
            calls++
            started.complete(Unit)
            release.await()
            listing
        }

        val owner = async { cache.getOrLoad(key, load = load) }
        started.await()
        val cancelledWaiter = async { cache.getOrLoad(key, load = load) }
        runCurrent()
        cancelledWaiter.cancelAndJoin()
        val remainingWaiter = async { cache.getOrLoad(key, load = load) }
        runCurrent()

        assertEquals(1, calls)
        release.complete(Unit)
        assertEquals(listing, owner.await())
        assertEquals(listing, remainingWaiter.await())
    }

    @Test
    fun removingHostCancelsItsInFlightRequest() = runTest {
        val cache = DirectoryListingCache(backgroundScope)
        val key = DirectoryListingKey("host", "path")
        val started = CompletableDeferred<Unit>()
        val request = async {
            cache.getOrLoad(key) {
                started.complete(Unit)
                CompletableDeferred<Listing>().await()
            }
        }
        started.await()

        cache.removeHost("host")
        runCurrent()

        assertTrue(request.isCancelled)
        assertNull(cache.get(key))
        assertEquals(listing, cache.getOrLoad(key) { listing })
    }
}
