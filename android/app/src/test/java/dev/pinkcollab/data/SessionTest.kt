package dev.pinkcollab.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    @Test
    fun `active follows runtime lifecycle including startup`() {
        assertTrue(session(status = "starting", runtimeAttached = false).isActive)
        assertTrue(session(status = "idle", runtimeAttached = true).isActive)
        assertTrue(session(status = "completed", runtimeAttached = true).isActive)
        assertFalse(session(status = "running", runtimeAttached = false).isActive)
        assertFalse(session(status = "completed", runtimeAttached = false).isActive)
    }

    private fun session(status: String, runtimeAttached: Boolean) = Session(
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
