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
        val sentIntent = store.state.value.getValue(first).intentId
        store.markSendStarted(first, sentVersion)
        store.setText(first, "new text")
        assertTrue(store.state.value.getValue(first).intentId != sentIntent)
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

    @Test fun draft_intent_stays_stable_until_a_send_starts() {
        val key = SessionKey("host", "session")
        val store = SessionDraftStore()
        store.setText(key, "hel")
        val saved = encodeDrafts(store.state.value)
        val intent = store.state.value.getValue(key).intentId
        store.setText(key, "hello")
        store.addFile(key, SelectedFile("content://note", "note.txt", "file_one"))
        assertEquals(intent, store.state.value.getValue(key).intentId)
        assertEquals(intent, decodeDrafts(saved).getValue(key).intentId)
        store.markSendStarted(key, store.state.value.getValue(key).version)
        store.setText(key, "hello again")
        assertTrue(intent != store.state.value.getValue(key).intentId)
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
        assertEquals(store.state.value.getValue(first).intentId, restored.getValue(first).intentId)
        assertEquals("second host", restored.getValue(second).text)
    }

    @Test fun cleared_drafts_are_not_kept_in_the_saved_snapshot() {
        val key = SessionKey("host", "session")
        val store = SessionDraftStore()
        store.setText(key, "done")
        store.clearIfVersion(key, store.state.value.getValue(key).version)
        assertEquals(emptyMap<SessionKey, SessionDraft>(), decodeDrafts(encodeDrafts(store.state.value)))
    }

    @Test fun terminal_failure_rotates_only_the_unchanged_draft_intent() {
        val key = SessionKey("host", "session")
        val store = SessionDraftStore()
        store.setText(key, "hello")
        val original = store.state.value.getValue(key)
        store.markSendStarted(key, original.version)
        store.rotateFailedIntent(key, original.version, original.intentId)
        val retried = store.state.value.getValue(key)
        assertEquals(original.text, retried.text)
        assertTrue(original.intentId != retried.intentId)
        store.rotateFailedIntent(key, original.version, original.intentId)
        assertEquals(retried.intentId, store.state.value.getValue(key).intentId)
    }

    @Test fun restored_started_draft_gets_a_new_intent_when_edited() {
        val key = SessionKey("host", "session")
        val beforeRestart = SessionDraftStore()
        beforeRestart.setText(key, "old")
        val old = beforeRestart.state.value.getValue(key)
        beforeRestart.markSendStarted(key, old.version)
        val restored = SessionDraftStore(decodeDrafts(encodeDrafts(beforeRestart.state.value)))
        assertTrue(restored.state.value.getValue(key).sendStarted)
        restored.setText(key, "new")
        assertTrue(restored.state.value.getValue(key).intentId != old.intentId)
        assertTrue(!restored.state.value.getValue(key).sendStarted)
    }
}
