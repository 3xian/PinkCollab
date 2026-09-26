package dev.pinkcollab.ui

import dev.pinkcollab.data.Attention
import dev.pinkcollab.data.AttentionType
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.RuntimeExecution
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.SessionStatus
import dev.pinkcollab.data.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPresentationTest {
    private val paired = PairedHost(Host("host", "Desktop", "windows", "", ""), "https://host", "secret", "client")
    private val online = HostState(paired, connection = ConnectionState.Online(1L))
    private val session = Session("session", "host", "/work", "Work", SessionStatus.Running, "Working",
        false, null, "", "", true, "generation", RuntimeExecution.Active)
    private val draft = SessionDraft(text = "hello")
    private fun controls(
        session: Session = this.session,
        host: HostState? = online,
        activity: SessionActivity = SessionActivity(),
        selectingFiles: Int = 0,
        showSavedHistory: Boolean = false,
    ) = sessionControls(SessionDetail(session, liveItems = listOf(TimelineItem("live", "user", "hi", "", ""))),
        host, draft, selectingFiles, activity, showSavedHistory)

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
        assertFalse(controls(session = session.copy(status = SessionStatus.Stopping)).inputEnabled)
        assertFalse(controls(session = session.copy(runtimeExecution = RuntimeExecution.Unknown)).inputEnabled)
    }

    @Test fun saved_history_switches_the_presented_mode() {
        assertFalse(controls().historyMode)
        assertTrue(controls(showSavedHistory = true).historyMode)
        assertEquals("Steer OMP…", controls().placeholder)
    }
}
