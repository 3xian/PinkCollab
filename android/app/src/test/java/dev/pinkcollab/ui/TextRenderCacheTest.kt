package dev.pinkcollab.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TextRenderCacheTest {
    @Test fun concurrent_requests_parse_once_and_changed_content_invalidates_cache() = runTest {
        val calls = AtomicInteger()
        val cache = TextRenderCache { text -> calls.incrementAndGet(); text.uppercase() }
        assertEquals(List(8) { "MESSAGE" }, List(8) { async { cache.get("message") } }.awaitAll())
        assertEquals(1, calls.get())
        assertEquals("MESSAGE EDIT", cache.get("message edit"))
        assertEquals(2, calls.get())
    }

    @Test fun entry_limit_evicts_least_recently_used_content() = runTest {
        val calls = mutableListOf<String>()
        val cache = TextRenderCache(maxEntries = 2) { text -> calls.add(text); text }
        cache.get("a")
        cache.get("b")
        cache.get("a")
        cache.get("c")
        cache.get("a")
        cache.get("b")
        assertEquals(listOf("a", "b", "c", "b"), calls)
    }

    @Test fun character_limit_evicts_content_and_does_not_retain_oversized_messages() = runTest {
        val calls = mutableListOf<String>()
        val cache = TextRenderCache(maxCharacters = 8) { text -> calls.add(text); text }
        cache.get("aa")
        cache.get("bbb")
        cache.get("aa")
        repeat(2) { cache.get("too large") }
        cache.get("aa")
        assertEquals(listOf("aa", "bbb", "aa", "too large", "too large"), calls)
    }

    @Test fun parsing_runs_off_the_callers_thread() = runTest {
        val caller = Thread.currentThread()
        val cache = TextRenderCache { text -> assertNotSame(caller, Thread.currentThread()); text }
        assertEquals("message", cache.get("message"))
    }
}
