package dev.pinkcollab.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    @Test fun `timing parses from runtime and remains optional for older gateways`() {
        val record = JSONObject("""{"id":"s","hostId":"h","cwd":"/w","title":"Work","createdAt":"","updatedAt":""}""")
        val runtime = JSONObject("""{"phase":"ready","execution":"active","workTiming":{"elapsedMs":83000,"running":true,"completed":false}}""")
        val timing = record.session(runtime).workTiming!!
        assertEquals(83_000L, timing.elapsedMs)
        assertTrue(timing.running)
        assertFalse(timing.completed)
        assertEquals(84_000L, timing.elapsedAt(timing.receivedAtNanos + 1_000_000_000))
        runtime.remove("workTiming")
        assertEquals(null, record.session(runtime).workTiming)
        assertEquals(null, record.session().workTiming)
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
