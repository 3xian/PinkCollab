package dev.pinkcollab.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    @Test
    fun `active follows runtime lifecycle including startup`() {
        assertTrue(session(status = SessionStatus.Starting, runtimeAttached = false).isActive)
        assertTrue(session(status = SessionStatus.Idle, runtimeAttached = true).isActive)
        assertFalse(session(status = SessionStatus.Running, runtimeAttached = false).isActive)
        assertFalse(session(status = SessionStatus.Idle, runtimeAttached = false).isActive)
    }

    private fun session(status: SessionStatus, runtimeAttached: Boolean) = Session(
        id = "session",
        hostId = "host",
        cwd = "workspace",
        title = "Task",
        status = status,
        activity = "",
        needsAttention = false,
        attention = null,
        createdAt = "2026-09-22T00:00:00Z",
        updatedAt = "2026-09-22T00:00:00Z",
        runtimeAttached = runtimeAttached,
    )
}
