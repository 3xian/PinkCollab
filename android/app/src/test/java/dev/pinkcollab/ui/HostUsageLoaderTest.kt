package dev.pinkcollab.ui

import dev.pinkcollab.data.UsageSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HostUsageLoaderTest {
    @Test fun requests_are_host_scoped_and_removed_hosts_cannot_reappear() = runTest {
        val pending = CompletableDeferred<UsageSnapshot>()
        var calls = 0
        val loader = HostUsageLoader(backgroundScope) { calls++; pending.await() }
        loader.load("host"); runCurrent()
        loader.load("host"); runCurrent()
        assertEquals(1, calls)
        loader.removeHost("host")
        pending.complete(UsageSnapshot(1, emptyList())); runCurrent()
        assertFalse(loader.state.value.containsKey("host"))
    }
    @Test fun overlapping_requests_preserve_each_hosts_loading_and_ready_states_in_either_completion_order() = runTest {
        for (order in listOf(listOf("first", "second"), listOf("second", "first"))) {
            val pending = order.associateWith { CompletableDeferred<UsageSnapshot>() }
            val snapshots = mapOf(
                "first" to UsageSnapshot(1, emptyList()),
                "second" to UsageSnapshot(2, emptyList()),
            )
            val loader = HostUsageLoader(backgroundScope) { pending.getValue(it).await() }
            loader.load("first"); runCurrent()
            loader.load("second"); runCurrent()
            assertEquals(mapOf("first" to LoadState.Loading, "second" to LoadState.Loading), loader.state.value)

            val completed = order.first()
            val waiting = order.last()
            pending.getValue(completed).complete(snapshots.getValue(completed)); runCurrent()
            assertEquals(
                mapOf(completed to LoadState.Ready(snapshots.getValue(completed)), waiting to LoadState.Loading),
                loader.state.value,
            )
            pending.getValue(waiting).complete(snapshots.getValue(waiting)); runCurrent()
            assertEquals(snapshots.mapValues { LoadState.Ready(it.value) }, loader.state.value)
        }
    }

    @Test fun completing_another_request_does_not_restore_a_removed_host() = runTest {
        val pending = CompletableDeferred<UsageSnapshot>()
        val snapshot = UsageSnapshot(2, emptyList())
        val loader = HostUsageLoader(backgroundScope) {
            if (it == "removed") UsageSnapshot(1, emptyList()) else pending.await()
        }
        loader.load("removed"); runCurrent()
        loader.load("pending"); runCurrent()
        loader.removeHost("removed")
        assertEquals(mapOf("pending" to LoadState.Loading), loader.state.value)
        pending.complete(snapshot); runCurrent()
        assertEquals(mapOf("pending" to LoadState.Ready(snapshot)), loader.state.value)
    }

    @Test fun failure_can_be_retried() = runTest {
        var fail = true
        val loader = HostUsageLoader(backgroundScope) {
            if (fail) error("Unavailable") else UsageSnapshot(1, emptyList())
        }
        loader.load("host"); runCurrent()
        assertTrue(loader.state.value["host"] is LoadState.Failed)
        fail = false; loader.load("host"); runCurrent()
        assertTrue(loader.state.value["host"] is LoadState.Ready)
    }
}
