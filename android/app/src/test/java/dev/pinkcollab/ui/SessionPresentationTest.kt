package dev.pinkcollab.ui

import dev.pinkcollab.data.Attention
import dev.pinkcollab.data.AttentionType
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.SavedHistory
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.SessionStatus
import dev.pinkcollab.data.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPresentationTest {
    private val paired = PairedHost(Host("host", "Desktop", "windows", "", ""), "https://host", "secret", "client")
    private val online = HostState(paired, connection = ConnectionState.Online(1L))
    private val session = Session("session", "host", "/work", "Work", SessionStatus.Running, "Working",
        false, null, "", "", true, "generation")
    private val draft = SessionDraft(text = "hello")

    @Test fun connection_recovery_does_not_show_history_or_refresh_errors() {
        val failed = SessionDetail(session, snapshotToken = null, savedHistory = SavedHistory.Failed)
        for (connection in listOf(ConnectionState.Connecting, ConnectionState.Synchronizing,
            ConnectionState.Reconnecting, ConnectionState.Offline())) {
            assertNull(sessionHistoryError(failed, online.copy(connection = connection), "Snapshot timed out"))
        }
        assertNull(sessionHistoryError(failed, null, "Snapshot timed out"))
    }

    @Test fun fresh_snapshot_clears_stale_refresh_error_but_retains_genuine_history_failure() {
        val detail = SessionDetail(session, snapshotToken = "new", savedHistory = SavedHistory.Loading)
        assertNull(sessionHistoryError(detail, online, "Snapshot timed out"))
        assertNull(sessionHistoryError(detail.copy(savedHistory = SavedHistory.Ready(null, emptyList(), null)),
            online, "Snapshot timed out"))
        assertEquals("Could not load message history",
            sessionHistoryError(detail.copy(savedHistory = SavedHistory.Failed), online, "Snapshot timed out"))
        assertEquals("Snapshot timed out", sessionHistoryError(detail.copy(snapshotToken = null), online,
            "Snapshot timed out"))
    }
    private fun controls(
        session: Session = this.session,
        host: HostState? = online,
        activity: SessionActivity = SessionActivity(),
        selectingFiles: Int = 0,
    ) = sessionControls(SessionDetail(session, liveItems = listOf(TimelineItem("live", "user", "hi", "", ""))),
        host, draft, selectingFiles, activity)

    @Test fun disconnected_host_disables_composer_and_controls() {
        val state = controls(host = online.copy(connection = ConnectionState.Offline()))
        assertFalse(state.inputEnabled)
        assertFalse(state.canSend)
        assertFalse(state.canStop)
    }

    @Test fun pending_operation_disables_its_actions_and_file_selection_blocks_send() {
        val sending = controls(activity = SessionActivity(send = true))
        assertFalse(sending.inputEnabled)
        assertFalse(sending.canChooseModel)
        assertTrue(sending.canStop)
        val selecting = controls(selectingFiles = 1)
        assertTrue(selecting.inputEnabled)
        assertFalse(selecting.canSend)
    }

    @Test fun attention_and_runtime_transitions_gate_input() {
        val attention = controls(session = session.copy(status = SessionStatus.NeedsInput,
            attention = Attention("request", AttentionType.Confirm, "Proceed?", emptyList()), needsAttention = true))
        assertFalse(attention.inputEnabled)
        assertTrue(attention.canInterrupt)
        assertFalse(controls(session = session.copy(status = SessionStatus.Starting)).inputEnabled)
        assertFalse(controls(session = session.copy(status = SessionStatus.Starting)).canChooseModel)
        assertFalse(controls(session = session.copy(status = SessionStatus.Stopping)).canChooseModel)
        assertFalse(controls(session = session.copy(status = SessionStatus.Stopping)).inputEnabled)
        assertFalse(controls(session = session.copy(status = SessionStatus.Starting)).inputEnabled)
    }

    @Test fun model_picker_can_start_a_new_runtime_after_exit() {
        val exited = session.copy(status = SessionStatus.Idle, runtimeAttached = false,
            generation = null)
        assertTrue(controls(session = exited).canChooseModel)
        assertFalse(controls(session = exited).attached)
        assertFalse(controls(session = exited, host = online.copy(connection = ConnectionState.Offline())).canChooseModel)
        assertFalse(controls(session = exited.copy(status = SessionStatus.Starting)).canChooseModel)
        assertTrue(controls(session = session.copy(generation = "new-generation")).canChooseModel)
    }

}
