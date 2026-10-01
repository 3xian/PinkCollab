package dev.pinkcollab.ui

import dev.pinkcollab.data.*
import org.junit.Assert.*
import org.junit.Test

class TodoPresentationTest {
    private val phases = listOf(TodoPhase("Ship", listOf(
        TodoTask("Design", TodoStatus.Completed),
        TodoTask("Build", TodoStatus.Active),
        TodoTask("Check", TodoStatus.Pending),
        TodoTask("Review", TodoStatus.Blocked, "waiting for access"),
        TodoTask("Old approach", TodoStatus.Abandoned),
    )))

    private fun todo(id: String, snapshot: List<TodoPhase>? = phases, error: Boolean = false) = TimelineItem(
        id, "tool", "Finished · todo", "", "",
        ToolTrace(id, "todo", ToolArguments(), "Output may be truncated", error, true, snapshot),
    )

    @Test fun structuredPlanPreservesEveryStatusAndBlocker() {
        val plan = (projectSessionTodo(listOf(todo("plan"))) as SessionTodo.Snapshot).plan
        assertEquals(phases, plan.phases)
        assertEquals("waiting for access", plan.phases.single().tasks[3].blocker)
        assertTrue(projectSessionTimeline(listOf(todo("plan"))).isEmpty())
    }

    @Test fun unavailableAndFailedResultsStayInGenericToolUi() {
        assertTrue(projectSessionTimeline(listOf(todo("bad", error = true))).single()
            is SessionDisplayItem.ActivityGroup)
        assertTrue(projectSessionTimeline(listOf(todo("unknown", snapshot = null))).single()
            is SessionDisplayItem.ActivityGroup)
        assertEquals(SessionTodo.Absent, projectSessionTodo(listOf(todo("bad", error = true))))
    }

    @Test fun planUpdatesDoNotSplitActivityGroupsOrAddTimelineRows() {
        fun read(id: String) = TimelineItem(id, "tool", "", "", "",
            ToolTrace(id, "read", ToolArguments(), "contents", false, true))
        val items = listOf(read("read1"), todo("first"), read("read2"), todo("last"))
        val group = projectSessionTimeline(items).single() as SessionDisplayItem.ActivityGroup
        assertEquals(2, group.operationCount)
        assertEquals("last", (projectSessionTodo(items) as SessionTodo.Snapshot).plan.trace.callId)
        assertTrue(projectSessionTimeline(items, SessionDisplayMode.Debug).all { it is SessionDisplayItem.Raw })
    }

    @Test fun liveSnapshotWinsAndClearDoesNotReviveSavedPlan() {
        val saved = projectSessionTodo(listOf(todo("saved")))
        val live = projectSessionTodo(listOf(todo("live")))
        assertEquals("live", latestSessionTodo(saved, live)!!.trace.callId)
        assertEquals("saved", latestSessionTodo(saved, SessionTodo.Absent)!!.trace.callId)
        assertNull(latestSessionTodo(saved, projectSessionTodo(listOf(todo("clear", emptyList())))))
    }

    @Test fun unavailableLatestSnapshotSuppressesBothOlderLiveAndSavedPlans() {
        val saved = projectSessionTodo(listOf(todo("saved")))
        val live = projectSessionTodo(listOf(todo("old"), todo("new", snapshot = null)))
        assertEquals(SessionTodo.Unavailable, live)
        assertNull(latestSessionTodo(saved, live))
        assertNull(latestSessionTodo(live, SessionTodo.Absent))
    }

    @Test fun failedUpdateDoesNotInvalidateTheLastSuccessfulSnapshot() {
        val live = projectSessionTodo(listOf(todo("old"), todo("failed", error = true)))
        assertEquals("old", latestSessionTodo(SessionTodo.Absent, live)!!.trace.callId)
    }
}
