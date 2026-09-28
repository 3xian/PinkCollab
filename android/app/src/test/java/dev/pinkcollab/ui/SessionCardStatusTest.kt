package dev.pinkcollab.ui

import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.RuntimeExecution
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCardStatusTest {
    private val online = ConnectionState.Online(0)
    private val session = Session("s", "h", "/work", "Work", SessionStatus.Idle, "", false, null,
        "", "", true, runtimeExecution = RuntimeExecution.Quiescent)

    @Test fun `idle distinguishes ready absent runtime and unknown execution`() {
        assertEquals(SessionCardStatus.Ready, sessionCardStatus(session, online))
        assertEquals(SessionCardStatus.Inactive, sessionCardStatus(session.copy(runtimeAttached = false), online))
        assertEquals(SessionCardStatus.Unknown, sessionCardStatus(session.copy(runtimeExecution = RuntimeExecution.Unknown), online))
    }

    @Test fun `connection loss masks retained running and attention states`() {
        val running = session.copy(status = SessionStatus.Running, runtimeExecution = RuntimeExecution.Active)
        assertEquals(SessionCardStatus.Working, sessionCardStatus(running, online))
        assertEquals(SessionCardStatus.Offline, sessionCardStatus(running, ConnectionState.Offline()))
        assertEquals(SessionCardStatus.Reconnecting, sessionCardStatus(running.copy(needsAttention = true), ConnectionState.Reconnecting))
    }

    @Test fun `lifecycle and attention take precedence over execution`() {
        val active = session.copy(runtimeExecution = RuntimeExecution.Active)
        assertEquals(SessionCardStatus.NeedsInput, sessionCardStatus(active.copy(needsAttention = true), online))
        assertEquals(SessionCardStatus.Starting, sessionCardStatus(active.copy(status = SessionStatus.Starting, runtimeAttached = false), online))
        assertEquals(SessionCardStatus.Stopping, sessionCardStatus(active.copy(status = SessionStatus.Stopping, needsAttention = true), online))
    }
}
