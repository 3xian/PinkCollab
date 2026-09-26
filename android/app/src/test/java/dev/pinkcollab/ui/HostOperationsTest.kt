package dev.pinkcollab.ui

import dev.pinkcollab.data.Listing
import dev.pinkcollab.data.DirectoryListingCache
import dev.pinkcollab.data.DirectoryListingKey
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.Workspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostOperationsTest {
    private val session = Session("session", "host", "/work", "Work", dev.pinkcollab.data.SessionStatus.Idle, "Ready", false, null,
        "2026-01-01", "2026-01-01", false)

    private class FakeActions : HostActions {
        val calls = mutableListOf<String>()
        var pairFailure: Exception? = null
        var directoryFailure: Exception? = null
        var firstDirectoryGate: CompletableDeferred<Unit>? = null
        var listingDelegate: (suspend (String, String, Boolean) -> Listing)? = null
        override suspend fun pair(url: String, token: String) {
            calls += "pair:$url:$token"
            pairFailure?.let { throw it }
        }
        override suspend fun refreshHost(hostId: String) { calls += "refresh:$hostId" }
        override fun reconnect(hostId: String) { calls += "reconnect:$hostId" }
        override fun reconnectUnavailableHosts() { calls += "reconnect-unavailable" }
        override suspend fun forget(hostId: String) { calls += "forget:$hostId" }
        override suspend fun listing(hostId: String, path: String, forceRefresh: Boolean): Listing {
            calls += "listing:$hostId:$path:$forceRefresh"
            listingDelegate?.let { return it(hostId, path, forceRefresh) }
            if (calls.count { it.startsWith("listing:") } == 1) firstDirectoryGate?.await()
            directoryFailure?.let { throw it }
            return Listing(path, null, listOf(Workspace("child", "$path/child")))
        }
        override fun prefetchListings(hostId: String, paths: List<String>) { calls += "prefetch:$hostId:${paths.single()}" }
        override suspend fun create(hostId: String, path: String): Session {
            calls += "create:$hostId:$path"
            return Session("session", hostId, path, "Work", dev.pinkcollab.data.SessionStatus.Idle, "Ready", false, null,
                "2026-01-01", "2026-01-01", false)
        }
    }

    @Test fun creation_emits_identity_and_refreshes_host() = runTest {
        val actions = FakeActions()
        val effects = mutableListOf<UiEffect>()
        val loaded = mutableListOf<Session>()
        val operations = HostOperations(backgroundScope, actions, effects::add, {}, {}, loaded::add)
        operations.create("host", "/work")
        runCurrent()
        assertEquals(listOf(session), loaded)
        assertEquals(UiEffect.SessionCreated(SessionKey("host", "session")), effects.single())
        assertEquals(listOf("create:host:/work", "refresh:host"), actions.calls)
        assertTrue(operations.operations.value.isEmpty())
    }

    @Test fun pairing_failure_is_a_targeted_effect_and_host_removal_cleans_up() = runTest {
        val actions = FakeActions().apply { pairFailure = IllegalStateException("invalid code") }
        val effects = mutableListOf<UiEffect>()
        val removed = mutableListOf<String>()
        val operations = HostOperations(backgroundScope, actions, effects::add, {}, removed::add, {})
        operations.pair("https://host", "token", 7L)
        runCurrent()
        assertEquals(UiEffect.PairingFailed(7L, "invalid code"), effects.single())
        assertFalse(effects.any { it is UiEffect.HostPaired })
        operations.forget("host")
        runCurrent()
        assertEquals(listOf("host"), removed)
        assertTrue("forget:host" in actions.calls)
    }

    @Test fun successful_pair_emits_the_original_attempt_id() = runTest {
        val effects = mutableListOf<UiEffect>()
        val operations = HostOperations(backgroundScope, FakeActions(), effects::add, {}, {}, {})
        operations.pair("https://host", "token", 9L)
        runCurrent()
        assertEquals(listOf(UiEffect.HostPaired(9L)), effects)
        assertTrue(operations.operations.value.isEmpty())
    }

    @Test fun forgetting_host_cancels_work_before_removal_and_cleans_state_afterward() = runTest {
        val actions = FakeActions()
        val events = mutableListOf<String>()
        val operations = HostOperations(backgroundScope, actions, {},
            { assertFalse("forget:host" in actions.calls); events += "cancel" },
            { assertTrue("forget:host" in actions.calls); events += "cleanup" }, {})

        operations.forget("host")
        runCurrent()

        assertEquals(listOf("cancel", "cleanup"), events)
    }

    @Test fun directory_loading_and_failure_are_feature_state() = runTest {
        val actions = FakeActions()
        val operations = HostOperations(backgroundScope, actions, {}, {}, {}, {})
        val key = BrowserKey("host", "/work")
        operations.loadDirectory(key)
        assertEquals(DirectoryLoad(key, LoadState.Loading), operations.directory.value)
        runCurrent()
        assertEquals("/work", (operations.directory.value?.state as LoadState.Ready).value.path)
        assertTrue("prefetch:host:/work/child" in actions.calls)
        actions.directoryFailure = IllegalStateException("offline")
        operations.loadDirectory(key, forceRefresh = true)
        runCurrent()
        assertEquals(DirectoryLoad(key, LoadState.Failed("offline")), operations.directory.value)
    }

    @Test fun stale_directory_result_cannot_replace_refresh_or_return_after_forget() = runTest {
        val gate = CompletableDeferred<Unit>()
        val actions = FakeActions().apply { firstDirectoryGate = gate }
        val operations = HostOperations(backgroundScope, actions, {}, {}, {}, {})
        val key = BrowserKey("host", "/work")
        operations.loadDirectory(key)
        runCurrent()
        operations.loadDirectory(key, forceRefresh = true)
        runCurrent()
        assertTrue(operations.directory.value?.state is LoadState.Ready)
        operations.forget("host")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(null, operations.directory.value)
        assertEquals(1, actions.calls.count { it.startsWith("prefetch:") })
    }

    @Test fun directory_state_only_retains_the_current_path() = runTest {
        val operations = HostOperations(backgroundScope, FakeActions(), {}, {}, {}, {})
        operations.loadDirectory(BrowserKey("host", "/first"))
        runCurrent()
        operations.loadDirectory(BrowserKey("host", "/second"))
        runCurrent()
        assertEquals(BrowserKey("host", "/second"), operations.directory.value?.key)
    }

    @Test fun retry_while_loading_uses_a_new_cache_request() = runTest {
        val gate = CompletableDeferred<Unit>()
        val cache = DirectoryListingCache(backgroundScope)
        val actions = FakeActions()
        var networkLoads = 0
        actions.listingDelegate = { hostId, path, force ->
            cache.getOrLoad(DirectoryListingKey(hostId, path), force) {
                networkLoads++
                if (networkLoads == 1) gate.await()
                Listing("$path/$networkLoads", null, emptyList())
            }
        }
        val operations = HostOperations(backgroundScope, actions, {}, {}, {}, {})
        val key = BrowserKey("host", "/work")
        operations.loadDirectory(key)
        runCurrent()
        operations.loadDirectory(key, forceRefresh = true)
        runCurrent()
        assertEquals(2, networkLoads)
        assertEquals("/work/2", (operations.directory.value?.state as LoadState.Ready).value.path)
        gate.complete(Unit)
        runCurrent()
        assertEquals("/work/2", (operations.directory.value?.state as LoadState.Ready).value.path)
    }
}
