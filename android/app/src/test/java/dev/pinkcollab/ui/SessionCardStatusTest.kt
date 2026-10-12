package dev.pinkcollab.ui

import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.SessionOrigin
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCardStatusTest {
    private val online = ConnectionState.Online(0)
    private val session = Session("s", "h", "/work", "Work", SessionStatus.Idle, "", false, null,
        "", "", true)

    @Test fun discovered_card_preserves_connectivity_without_a_runtime() {
        val discovered = session.copy(runtimeAttached = false, generation = null, origin = SessionOrigin.Discovered)
        assertEquals(SessionCardStatus.OnHost, sessionCardStatus(discovered, online))
        assertEquals(SessionCardStatus.Offline, sessionCardStatus(discovered, ConnectionState.Offline()))
        assertEquals(SessionCardStatus.Syncing, sessionCardStatus(discovered, ConnectionState.Synchronizing))
        assertEquals(SessionCardStatus.Connecting, sessionCardStatus(discovered, ConnectionState.Connecting))
        assertEquals(SessionCardStatus.Offline, sessionCardStatus(discovered, null))
    }

    @Test fun `idle distinguishes ready absent runtime and startup`() {
        assertEquals(SessionCardStatus.Ready, sessionCardStatus(session, online))
        assertEquals(SessionCardStatus.Inactive, sessionCardStatus(session.copy(runtimeAttached = false), online))
        assertEquals(SessionCardStatus.Starting, sessionCardStatus(session.copy(status = SessionStatus.Starting), online))
    }

    @Test fun `connection loss masks retained running and attention states`() {
        val running = session.copy(status = SessionStatus.Running)
        assertEquals(SessionCardStatus.Working, sessionCardStatus(running, online))
        assertEquals(SessionCardStatus.Offline, sessionCardStatus(running, ConnectionState.Offline()))
        assertEquals(SessionCardStatus.Offline, sessionCardStatus(running.copy(needsAttention = true), ConnectionState.Offline()))
    }

    @Test fun `lifecycle and attention take precedence over execution`() {
        val active = session.copy(status = SessionStatus.Running)
        assertEquals(SessionCardStatus.NeedsInput, sessionCardStatus(active.copy(needsAttention = true), online))
        assertEquals(SessionCardStatus.Starting, sessionCardStatus(active.copy(status = SessionStatus.Starting, runtimeAttached = false), online))
        assertEquals(SessionCardStatus.Stopping, sessionCardStatus(active.copy(status = SessionStatus.Stopping, needsAttention = true), online))
    }
}
