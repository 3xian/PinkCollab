package dev.pinkcollab.ui

import dev.pinkcollab.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ToolDetailsTest {
    @Test fun cancelled_new_version_is_fetched_when_reopened() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val controller = ToolDetailsController { _, _ ->
            reads++
            if (reads == 2) { started.complete(Unit); release.await() }
            ToolDetailPage("body-$reads", "v$reads", null, true, "resume", false)
        }
        controller.load("call", "v1")
        val second = async { controller.load("call", "v2") }
        started.await()
        second.cancel()
        second.join()
        controller.load("call", "v2")
        assertEquals(3, reads)
        assertEquals("v3", controller.state("call", "v2").version)
    }

    @Test fun first_expansion_is_cached_and_new_version_refreshes() = runTest {
        var reads = 0
        val controller = ToolDetailsController { _, _ -> reads++; ToolDetailPage("full output", "v$reads", null, true) }
        assertEquals(0, reads)
        controller.load("call", "v1")
        controller.load("call", "v1")
        assertEquals(1, reads)
        assertEquals("full output", controller.state("call", "v1").text)
        controller.load("call", "v2")
        assertEquals(2, reads)
    }

    @Test fun concurrent_expansions_share_one_request() = runTest {
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val controller = ToolDetailsController { _, _ -> reads++; release.await(); ToolDetailPage("output", "v", null, true) }
        val first = async { controller.load("call", "v") }
        yield()
        controller.load("call", "v")
        release.complete(Unit)
        first.await()
        assertEquals(1, reads)
    }

    @Test fun failure_is_local_and_can_be_retried() = runTest {
        var fail = true
        val controller = ToolDetailsController { _, _ ->
            if (fail) throw java.io.IOException("offline")
            ToolDetailPage("output", "v", null, true)
        }
        controller.load("call", "v")
        assertEquals("offline", controller.state("call", "v").error)
        fail = false
        controller.load("call", "v", retry = true)
        assertNull(controller.state("call", "v").error)
        assertEquals("output", controller.state("call", "v").text)
    }

    @Test fun stale_next_page_restarts_without_appending_different_versions() = runTest {
        var firstReads = 0
        val controller = ToolDetailsController { _, cursor ->
            if (cursor != null) throw GatewayHttpException(409, "stale_detail", "changed")
            firstReads++
            if (firstReads == 1) ToolDetailPage("old", "v1", "next", false)
            else ToolDetailPage("new", "v2", null, true)
        }
        controller.load("call", "v1")
        controller.load("call", "v1", more = true)
        assertEquals("new", controller.state("call", "v1").text)
        assertNull(controller.state("call", "v1").nextCursor)
    }

    @Test fun append_and_completion_reuse_the_received_prefix_without_duplicate_arguments() = runTest {
        val cursors = mutableListOf<String?>()
        val controller = ToolDetailsController { _, cursor ->
            cursors += cursor
            when (cursors.size) {
                1 -> ToolDetailPage("Arguments\ncommand\n\nResult\nfirst", "running", "prefix1", false, "prefix1", false)
                2 -> ToolDetailPage(" second", "running", "prefix2", false, "prefix2", false)
                else -> ToolDetailPage("", "final", null, true, "prefix2", false)
            }
        }
        controller.load("call", "running")
        controller.load("call", "running", more = true)
        controller.load("call", "final")
        assertEquals(listOf(null, "prefix1", "prefix2"), cursors)
        assertEquals("Arguments\ncommand\n\nResult\nfirst second", controller.state("call", "final").text)
        assertTrue(controller.state("call", "final").completed)
    }
}
