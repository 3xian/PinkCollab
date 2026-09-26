package dev.pinkcollab.ui

import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionStatus
import dev.pinkcollab.data.RuntimeExecution
import dev.pinkcollab.data.AttentionResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionOperationsTest {
    private val session = Session("session", "host", "/tmp", "Work", SessionStatus.Running, "Working", false, null,
        "2026-01-01", "2026-01-01", true, "generation", RuntimeExecution.Active)
    private val key = SessionKey("host", "session")

    private class FakeActions(
        private val onPrompt: suspend () -> Unit = {},
        private val uncancellableUpload: Boolean = false,
    ) : SessionActions {
        val uploadStarted = CompletableDeferred<Unit>()
        val finishUpload = CompletableDeferred<Unit>()
        val historyStarted = CompletableDeferred<Unit>()
        val finishHistory = CompletableDeferred<Unit>()
        val promptExited = CompletableDeferred<Unit>()
        val commands = mutableListOf<String>()

        override suspend fun upload(session: Session, file: SelectedFile) {
            uploadStarted.complete(Unit)
            if (uncancellableUpload) withContext(NonCancellable) { finishUpload.await() }
            else finishUpload.await()
        }

        override suspend fun prompt(session: Session, message: String, fileIds: List<String>, intentId: String) {
            commands += "prompt"
            try { onPrompt() } finally { promptExited.complete(Unit) }
        }
        override suspend fun command(session: Session, command: SessionUserCommand) { commands += command.wire }
        override suspend fun respond(session: Session, response: AttentionResponse) { commands += "respond" }

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

    @Test fun control_does_not_wait_for_an_in_flight_prompt_to_finish_local_cancellation() = runTest {
        for (control in listOf(SessionUserCommand.Stop, SessionUserCommand.Interrupt)) {
            val drafts = SessionDraftStore()
            drafts.setText(key, "send me")
            val promptStarted = CompletableDeferred<Unit>()
            val finishPrompt = CompletableDeferred<Unit>()
            val actions = FakeActions(onPrompt = {
                promptStarted.complete(Unit)
                withContext(NonCancellable) { finishPrompt.await() }
            })
            val coordinator = SessionOperations(backgroundScope, actions, drafts, drafts::clearIfVersion) {}

            coordinator.send(session)
            runCurrent()
            promptStarted.await()
            coordinator.send(session)
            coordinator.command(session, control)
            runCurrent()

            assertEquals(listOf("prompt", control.wire), actions.commands)
            assertTrue(!actions.promptExited.isCompleted)
            finishPrompt.complete(Unit)
            runCurrent()

            assertTrue(actions.promptExited.isCompleted)
            assertEquals(listOf("prompt", control.wire), actions.commands)
        }
    }

    @Test fun stop_does_not_wait_for_a_blocked_file_provider_and_cannot_submit_its_prompt_later() = runTest {
        val drafts = SessionDraftStore()
        drafts.setText(key, "send me")
        drafts.addFile(key, SelectedFile("content://file", "attachment", "file_one"))
        val actions = FakeActions(uncancellableUpload = true)
        val coordinator = SessionOperations(backgroundScope, actions, drafts, drafts::clearIfVersion) {}

        coordinator.send(session)
        runCurrent()
        actions.uploadStarted.await()
        coordinator.command(session, SessionUserCommand.Stop)
        runCurrent()

        assertEquals(listOf("stop"), actions.commands)
        assertTrue(coordinator.operations.value.activity(key).send)
        actions.finishUpload.complete(Unit)
        runCurrent()
        assertEquals(listOf("stop"), actions.commands)
        assertEquals("send me", drafts.state.value.getValue(key).text)
    }

    @Test fun forgetting_host_cancels_upload_before_prompt_submission() = runTest {
        val drafts = SessionDraftStore()
        drafts.setText(key, "send me")
        drafts.addFile(key, SelectedFile("content://file", "attachment", "file_one"))
        val actions = FakeActions()
        val coordinator = SessionOperations(backgroundScope, actions, drafts, drafts::clearIfVersion) {}

        coordinator.send(session)
        runCurrent()
        actions.uploadStarted.await()
        coordinator.cancelHost("host")
        runCurrent()
        actions.finishUpload.complete(Unit)
        runCurrent()

        assertTrue(actions.commands.isEmpty())
        assertTrue(coordinator.operations.value.isEmpty())
        assertEquals("send me", drafts.state.value.getValue(key).text)
    }
}
