package dev.pinkcollab.ui

import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionOperationsTest {
    private val session = Session("session", "host", "/tmp", "Work", "running", "Working", false, null,
        "2026-01-01", "2026-01-01", true, "generation", "active")
    private val key = SessionKey("host", "session")

    private class FakeActions : SessionActions {
        val uploadStarted = CompletableDeferred<Unit>()
        val finishUpload = CompletableDeferred<Unit>()
        val historyStarted = CompletableDeferred<Unit>()
        val finishHistory = CompletableDeferred<Unit>()
        val commands = mutableListOf<String>()

        override suspend fun upload(session: Session, file: SelectedFile) {
            uploadStarted.complete(Unit)
            finishUpload.await()
        }

        override suspend fun command(session: Session, command: String, body: JSONObject, intentId: String?) {
            commands += command
        }

        override suspend fun selectModel(session: Session, model: ModelInfo) = Unit
        override suspend fun setThinkingLevel(session: Session, level: String) = Unit
        override suspend fun loadSavedHistory(session: Session) {
            historyStarted.complete(Unit)
            finishHistory.await()
        }
        override suspend fun loadEarlierHistory(session: Session) = Unit
    }

    @Test fun stop_cancels_upload_without_submitting_prompt() = runTest {
        val drafts = SessionDraftStore()
        drafts.setText(key, "send me")
        drafts.addFile(key, SelectedFile("content://file", "attachment", "file_one"))
        val actions = FakeActions()
        val coordinator = SessionOperations(backgroundScope, actions, drafts, drafts::clearIfVersion) {}

        coordinator.send(session)
        runCurrent()
        actions.uploadStarted.await()
        coordinator.command(session, SessionUserCommand.Stop)
        runCurrent()

        assertEquals(listOf("stop"), actions.commands)
        assertEquals("send me", drafts.state.value.getValue(key).text)
        assertTrue(!coordinator.operations.value.activity(key).send)
    }

    @Test fun stop_runs_while_history_request_is_pending() = runTest {
        val actions = FakeActions()
        val coordinator = SessionOperations(backgroundScope, actions, SessionDraftStore(), { _, _ -> }) {}

        coordinator.loadSavedHistory(session)
        runCurrent()
        actions.historyStarted.await()
        coordinator.command(session, SessionUserCommand.Stop)
        runCurrent()

        assertEquals(listOf("stop"), actions.commands)
        assertTrue(coordinator.operations.value.activity(key).history)
    }
}
