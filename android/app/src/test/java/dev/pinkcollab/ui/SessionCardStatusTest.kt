package dev.pinkcollab.ui

import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCardStatusTest {
    private val online = ConnectionState.Online(0)
    private val session = Session("s", "h", "/work", "Work", SessionStatus.Idle, "", false, null,
        "", "", true)

    @Test fun `idle distinguishes ready absent runtime and startup`() {
        assertEquals(SessionCardStatus.Ready, sessionCardStatus(session, online))
        assertEquals(SessionCardStatus.Inactive, sessionCardStatus(session.copy(runtimeAttached = false), online))
        assertEquals(SessionCardStatus.Starting, sessionCardStatus(session.copy(status = SessionStatus.Starting), online))
    }

    @Test fun `connection loss masks retained running and attention states`() {
        val running = session.copy(status = SessionStatus.Running)
        assertEquals(SessionCardStatus.Working, sessionCardStatus(running, online))
        assertEquals(SessionCardStatus.Offline, sessionCardStatus(running, ConnectionState.Offline()))
        assertEquals(SessionCardStatus.Reconnecting, sessionCardStatus(running.copy(needsAttention = true), ConnectionState.Reconnecting))
    }

    @Test fun `lifecycle and attention take precedence over execution`() {
        val active = session.copy(status = SessionStatus.Running)
        assertEquals(SessionCardStatus.NeedsInput, sessionCardStatus(active.copy(needsAttention = true), online))
        assertEquals(SessionCardStatus.Starting, sessionCardStatus(active.copy(status = SessionStatus.Starting, runtimeAttached = false), online))
        assertEquals(SessionCardStatus.Stopping, sessionCardStatus(active.copy(status = SessionStatus.Stopping, needsAttention = true), online))
    }
}
