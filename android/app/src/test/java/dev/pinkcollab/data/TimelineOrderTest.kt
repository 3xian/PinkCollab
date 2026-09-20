package dev.pinkcollab.data

import org.junit.Assert.*
import org.junit.Test

class TimelineOrderTest {
    @Test fun timestampsAreComparedAsInstants() {
        assertTrue(compareTimestamps("2026-09-14T00:00:00.100Z", "2026-09-14T00:00:00Z") > 0)
        assertEquals(0, compareTimestamps("2026-09-14T08:00:00+08:00", "2026-09-14T00:00:00Z"))
    }
    @Test fun staleSnapshotCannotOverwriteLiveToolResult() {
        val started = TimelineItem("tool-1", "tool", "Testing", "", "2026-09-14T00:00:00Z")
        val finished = started.copy(text = "Finished", timestamp = "2026-09-14T00:00:00.001Z")
        val merged = mergeTimeline(listOf(started), listOf(finished))
        assertEquals(1, merged.size)
        assertEquals("Finished", merged.single().text)
    }

    /**
     * The gateway pins a tool item to the timestamp of its call, so an update and its start can
     * carry the same one. Which side wins then is a rule, not an accident: the preferred argument.
     */
    @Test fun timestampTiesAreDecidedByThePreferredSide() {
        val snapshot = TimelineItem("tool-1", "tool", "Running · bash", "", "2026-09-14T00:00:00Z")
        val event = snapshot.copy(text = "Finished · bash")

        assertEquals("Running · bash", mergeTimeline(listOf(snapshot), listOf(event)).single().text)
        assertEquals("Finished · bash", mergeTimeline(listOf(event), listOf(snapshot)).single().text)
    }
}
