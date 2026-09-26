package dev.pinkcollab.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDraftStoreTest {
    @Test fun old_send_result_cannot_clear_a_newer_draft_or_another_session() {
        val first = SessionKey("host", "first")
        val second = SessionKey("host", "second")
        val store = SessionDraftStore()
        store.setText(first, "original")
        val sentVersion = store.state.value.getValue(first).version
        store.setText(first, "new text")
        store.setText(second, "keep me")
        store.clearIfVersion(first, sentVersion)
        assertEquals("new text", store.state.value.getValue(first).text)
        assertEquals("keep me", store.state.value.getValue(second).text)
        store.clearIfVersion(first, store.state.value.getValue(first).version)
        assertEquals(null, store.state.value[first])
        assertEquals("keep me", store.state.value.getValue(second).text)
        store.setText(first, "new after clear")
        assertTrue(store.state.value.getValue(first).version > sentVersion)
    }

    @Test fun saved_drafts_restore_per_session() {
        val first = SessionKey("one", "session")
        val second = SessionKey("two", "session")
        val store = SessionDraftStore()
        store.setText(first, "first host")
        store.addFile(first, SelectedFile("content://first", "note.txt", "file_one"))
        store.setText(second, "second host")
        val restored = decodeDrafts(encodeDrafts(store.state.value))
        assertEquals("first host", restored.getValue(first).text)
        assertEquals("content://first", restored.getValue(first).files.single().uri)
        assertEquals("second host", restored.getValue(second).text)
    }

    @Test fun cleared_drafts_are_not_kept_in_the_saved_snapshot() {
        val key = SessionKey("host", "session")
        val store = SessionDraftStore()
        store.setText(key, "done")
        store.clearIfVersion(key, store.state.value.getValue(key).version)
        assertEquals(emptyMap<SessionKey, SessionDraft>(), decodeDrafts(encodeDrafts(store.state.value)))
    }
}
