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

    @Test fun `timing parses from runtime and remains optional when work start is unknown`() {
        val record = JSONObject("""{"id":"s","hostId":"h","cwd":"/w","title":"Work","createdAt":"","updatedAt":""}""")
        val runtime = JSONObject("""{"state":"running","generation":"g","workTiming":{"elapsedMs":83000,"running":true,"completed":false}}""")
        val timing = record.session(runtime).workTiming!!
        assertEquals(83_000L, timing.elapsedMs)
        assertTrue(timing.running)
        assertFalse(timing.completed)
        assertEquals(84_000L, timing.elapsedAt(timing.receivedAtNanos + 1_000_000_000))
        runtime.remove("workTiming")
        assertEquals(null, record.session(runtime).workTiming)
        assertEquals(null, record.session().workTiming)
    }

    @Test fun `speed parses optional finite nonnegative runtime samples`() {
        val record = JSONObject("""{"id":"s","hostId":"h","cwd":"/w","title":"Work","createdAt":"","updatedAt":""}""")
        val runtime = JSONObject("""{"state":"running","tokensPerSecond":42.3}""")
        assertEquals(42.3, record.session(runtime).tokensPerSecond)
        runtime.put("tokensPerSecond", 0.0)
        assertEquals(0.0, record.session(runtime).tokensPerSecond)
        for (sample in listOf(-1.0, "NaN", "Infinity", JSONObject.NULL)) {
            runtime.put("tokensPerSecond", sample)
            assertEquals(null, record.session(runtime).tokensPerSecond)
        }
        runtime.remove("tokensPerSecond")
        assertEquals(null, record.session(runtime).tokensPerSecond)
        assertEquals(null, record.session().tokensPerSecond)
    }

    @Test fun `activity parses independently with a legacy fallback`() {
        val record = JSONObject("""{"id":"s","hostId":"h","cwd":"/w","title":"Work","createdAt":"created","updatedAt":"metadata"}""")
        assertEquals("metadata", record.session().lastActivityAt)
        record.put("lastActivityAt", "chat")
        assertEquals("chat", record.session().lastActivityAt)
        assertEquals("metadata", record.session().updatedAt)
        assertEquals("created", record.session().createdAt)
        record.put("lastActivityAt", JSONObject.NULL)
        assertEquals("metadata", record.session().lastActivityAt)
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
