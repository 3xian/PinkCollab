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
}
