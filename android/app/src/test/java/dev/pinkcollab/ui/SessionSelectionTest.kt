package dev.pinkcollab.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSelectionTest {
    @Test fun selected_session_round_trip_includes_host() {
        val selected = SessionKey("host-b", "same-id")
        assertEquals(selected, restoreSelectedSession(selected.savedSelection()))
        assertEquals("host-b", restoreSelectedSession(selected.savedSelection())?.hostId)
    }

    @Test fun incomplete_selection_is_ignored() {
        assertNull(restoreSelectedSession(listOf("same-id")))
    }
}
