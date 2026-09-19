package dev.pinkcollab.ui

import dev.pinkcollab.data.TimelineItem
import dev.pinkcollab.data.ToolTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDisplayProjectionTest {
    private fun message(id: String, kind: String, text: String) =
        TimelineItem(id, kind, text, "", "2026-09-20T00:00:00Z")

    private fun tool(
        id: String,
        name: String,
        arguments: String = "{}",
        result: String = "ok",
        error: Boolean = false,
    ) = TimelineItem(
        id = id,
        kind = "tool",
        text = if (error) "Tool failed · $name" else "Finished · $name",
        detail = result,
        timestamp = "2026-09-20T00:00:00Z",
        tool = ToolTrace(id, name, arguments, result, error, completed = true),
    )

    @Test fun conciseProjectionGroupsStagesAndAssistantTextEndsAGroup() {
        val projected = projectSessionTimeline(
            listOf(
                message("u", "user", "修复登录"),
                tool("r", "read"),
                message("compact", "notice", "Compacting context"),
                tool("g", "grep"),
                tool("e1", "edit", """{"path":"src/Login.kt","old":"a","new":"b"}"""),
                tool("e2", "write", """{"path":"src/Login.kt","content":"b"}"""),
                message("a", "assistant", "已找到原因。"),
                tool("t", "bash", result = "3 tests passed"),
            ),
        )

        assertEquals(5, projected.size)
        val explore = projected[1] as SessionDisplayItem.ActivityGroup
        assertEquals(ActivityStage.Explore, explore.stage)
        assertEquals(2, explore.operationCount)
        val change = projected[2] as SessionDisplayItem.ActivityGroup
        assertEquals(listOf("src/Login.kt"), change.files)
        assertTrue(change.details.contains("--- a/src/Login.kt"))
        assertTrue(change.details.contains("+b"))
        assertTrue(projected[3] is SessionDisplayItem.Message)
        val execute = projected[4] as SessionDisplayItem.ActivityGroup
        assertEquals(ActivityStatus.Succeeded, execute.status)
        assertEquals("3 tests passed", execute.summary)
    }

    @Test fun conciseProjectionHidesUnknownSuccessfulToolsAndKeepsShortErrors() {
        val projected = projectSessionTimeline(
            listOf(
                tool("metadata", "set_metadata", result = "saved"),
                tool("test", "test", result = "stack line\nAssertion failed", error = true),
            ),
        )

        assertEquals(1, projected.size)
        val failure = projected.single() as SessionDisplayItem.ActivityGroup
        assertEquals(ActivityStatus.Failed, failure.status)
        assertEquals("Assertion failed", failure.summary)
        assertTrue(failure.details.contains("stack line"))
    }

    @Test fun debugProjectionPreservesEveryRawTimelineItem() {
        val source = listOf(message("n", "notice", "Compacting context"), tool("x", "custom"))
        val projected = projectSessionTimeline(source, SessionDisplayMode.Debug)
        assertEquals(2, projected.size)
        assertTrue(projected.all { it is SessionDisplayItem.Raw })
    }
}
