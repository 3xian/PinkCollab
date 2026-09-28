package dev.pinkcollab.ui

import dev.pinkcollab.data.*
import org.junit.Assert.*
import org.junit.Test

class SessionDisplayCacheTest {
    private val session = Session("session", "host", "/work", "Work", SessionStatus.Idle,
        "", false, null, "", "", false)
    private val message = TimelineItem("old", "user", "Cached message", "", "")
    private val cached = sessionDetailForDisplay(SessionDetail(session, snapshotToken = "old-subscription",
        savedHistory = SavedHistory.Ready("source", listOf(message), "old-cursor")), null)!!

    @Test fun cache_survives_detail_refresh_without_reusing_subscription_or_pagination() {
        val displayed = sessionDetailForDisplay(null, cached)!!
        assertEquals(listOf(message), displayed.historyItems)
        assertEquals(SavedHistory.Loading, displayed.detail.savedHistory)
        assertNull(displayed.detail.savedHistory.nextCursor)
        assertNull(displayed.detail.snapshotToken)
    }

    @Test fun loading_or_failed_history_keeps_messages_with_fresh_session_state() {
        for (history in listOf(SavedHistory.Loading, SavedHistory.Failed)) {
            val current = SessionDetail(session.copy(title = "Fresh title"),
                snapshotToken = "new-subscription", savedHistory = history)
            val displayed = sessionDetailForDisplay(current, cached)!!
            assertEquals(current, displayed.detail)
            assertEquals(listOf(message), displayed.historyItems)
            assertNull(displayed.detail.savedHistory.nextCursor)
        }
    }

    @Test fun authoritative_empty_history_replaces_cache() {
        val current = SessionDetail(session, savedHistory = SavedHistory.Ready("new", emptyList(), null))
        assertEquals(SessionDisplay(current, emptyList()), sessionDetailForDisplay(current, cached))
        assertEquals(current.copy(savedHistory = SavedHistory.None),
            sessionDetailForDisplay(current.copy(savedHistory = SavedHistory.None), cached)?.detail)
    }

    @Test fun empty_cache_does_not_turn_loading_or_failure_into_known_empty() {
        val emptyCache = sessionDetailForDisplay(SessionDetail(session,
            savedHistory = SavedHistory.Ready("source", emptyList(), null)), null)
        for (history in listOf(SavedHistory.Loading, SavedHistory.Failed)) {
            val displayed = sessionDetailForDisplay(SessionDetail(session, savedHistory = history), emptyCache)!!
            assertEquals(history, displayed.detail.savedHistory)
            assertFalse(displayed.detail.savedHistory.knownEmpty)
            assertTrue(displayed.historyItems.isEmpty())
        }
    }

    @Test fun repeated_refresh_states_keep_messages_until_authoritative_replacement() {
        val loading = sessionDetailForDisplay(SessionDetail(session, savedHistory = SavedHistory.Loading), cached)
        val failed = sessionDetailForDisplay(SessionDetail(session, savedHistory = SavedHistory.Failed), loading)
        assertEquals(listOf(message), failed?.historyItems)
        val empty = sessionDetailForDisplay(SessionDetail(session, savedHistory = SavedHistory.None), failed)
        assertTrue(empty!!.historyItems.isEmpty())
    }
}
