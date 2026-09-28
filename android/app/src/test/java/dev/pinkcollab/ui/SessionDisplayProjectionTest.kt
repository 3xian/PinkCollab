package dev.pinkcollab.ui

import dev.pinkcollab.data.TimelineItem
import dev.pinkcollab.data.ToolArguments
import dev.pinkcollab.data.ToolTrace
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDisplayProjectionTest {
    private fun message(id: String, kind: String, text: String) =
        TimelineItem(id, kind, text, "", "2026-09-20T00:00:00Z")

    private fun tool(
        id: String,
        name: String,
        arguments: ToolArguments = ToolArguments(),
        result: String = "ok",
        error: Boolean = false,
        completed: Boolean = true,
    ) = TimelineItem(
        id = id,
        kind = "tool",
        text = if (error) "Tool failed · $name" else "Finished · $name",
        // The gateway keeps tool payloads inside `tool`; `detail` is empty for these items.
        detail = "",
        timestamp = "2026-09-20T00:00:00Z",
        tool = ToolTrace(id, name, arguments, result, error, completed),
    )

    @Test fun conciseProjectionGroupsStagesAndAssistantTextEndsAGroup() {
        val projected = projectSessionTimeline(
            listOf(
                message("u", "user", "Fix login"),
                tool("r", "read"),
                message("compact", "notice", "Compacting context"),
                tool("g", "grep"),
                tool("e1", "edit", arguments("src/Login.kt", old = "a", new = "b")),
                tool("e2", "write", arguments("src/Login.kt", content = "b")),
                message("a", "assistant", "Found the cause."),
                tool("t", "bash", result = "3 tests passed"),
            ),
        )

        assertEquals(5, projected.size)
        val explore = projected[1] as SessionDisplayItem.ActivityGroup
        assertEquals(ActivityStage.Explore, explore.stage)
        assertEquals(2, explore.operationCount)
        val change = projected[2] as SessionDisplayItem.ActivityGroup
        assertEquals(listOf("src/Login.kt"), change.files)
        assertTrue(change.operations[0].details.contains("--- a/src/Login.kt"))
        assertTrue(change.operations[0].details.contains("+b"))
        assertEquals(ActivityDetailKind.Diff, change.operations[0].detailKind)
        assertEquals(ActivityDetailKind.Content, change.operations[1].detailKind)
        assertEquals("2026-09-20T00:00:00Z", (projected[3] as SessionDisplayItem.Message).timestamp)
        val execute = projected[4] as SessionDisplayItem.ActivityGroup
        assertEquals(ActivityStatus.Succeeded, execute.status)
        assertTrue(execute.operations.single().details.contains("3 tests passed"))
    }

    @Test fun unknownToolsRetainTheirOwnOperationAndErrorsRemainAccessible() {
        val projected = projectSessionTimeline(
            listOf(
                tool("metadata", "set_metadata", result = "saved"),
                tool("test", "test", result = "stack line\nAssertion failed", error = true),
            ),
        )

        val metadata = projected[0] as SessionDisplayItem.ActivityGroup
        assertEquals("set_metadata", metadata.operations.single().name)
        assertEquals(ActivityStatus.Succeeded, metadata.status)
        assertTrue(metadata.operations.single().details.contains("saved"))
        val failure = projected[1] as SessionDisplayItem.ActivityGroup
        assertEquals(ActivityStatus.Failed, failure.status)
        assertTrue(failure.operations.single().details.contains("stack line"))
        assertEquals(ActivityDetailKind.Error, failure.operations.single().detailKind)
    }

    @Test fun debugProjectionPreservesEveryRawTimelineItem() {
        val source = listOf(message("n", "notice", "Compacting context"), tool("x", "custom"))
        val projected = projectSessionTimeline(source, SessionDisplayMode.Debug)
        assertEquals(2, projected.size)
        assertTrue(projected.all { it is SessionDisplayItem.Raw })
    }

    @Test fun writeWithoutBeforeStateIsPresentedAsContentNotDiff() {
        val projected = projectSessionTimeline(
            listOf(tool("write", "write", arguments("src/Login.kt", content = "new contents"))),
        )

        val change = (projected.single() as SessionDisplayItem.ActivityGroup).operations.single()
        assertEquals(ActivityDetailKind.Content, change.detailKind)
        assertTrue(change.details.contains("new contents"))
        assertTrue(!change.details.contains("--- a/"))
    }

    @Test fun runningOperationTakesPrecedenceWithoutDiscardingEarlierFailure() {
        val group = projectSessionTimeline(
            listOf(
                tool("failed", "bash", ToolArguments(strings = mapOf("command" to "false")), error = true),
                tool("running", "bash", ToolArguments(strings = mapOf("i" to "Reading repository state", "command" to "git status")), result = "", completed = false),
            ),
        ).single() as SessionDisplayItem.ActivityGroup

        assertEquals(ActivityStatus.Running, group.status)
        assertEquals(1, group.failureCount)
        assertEquals("Reading repository state", group.action)
        assertEquals("git status", group.summary)
        assertEquals(listOf(ActivityStatus.Failed, ActivityStatus.Running), group.operations.map { it.status })
    }

    @Test fun explorationRetainsIntentQueryAndTargetWithoutNeedingOutput() {
        val group = projectSessionTimeline(
            listOf(
                tool("glob", "functions.glob", ToolArguments(strings = mapOf("path" to "src/**/*.kt")), result = ""),
                tool("search", "web_search", ToolArguments(strings = mapOf("description" to "Finding API documentation", "query" to "Compose state", "url" to "https://developer.android.com")), result = "documentation"),
            ),
        ).single() as SessionDisplayItem.ActivityGroup

        assertEquals(ActivityStage.Explore, group.stage)
        assertEquals("Finding API documentation", group.action)
        assertTrue(group.summary.contains("Compose state"))
        assertTrue(group.summary.contains("https://developer.android.com"))
        assertEquals("src/**/*.kt", group.operations[0].target)
        assertTrue(group.operations[1].details.contains("documentation"))
    }

    @Test fun successfulCommandsKeepCommandAndOutputRatherThanInferringVerification() {
        val group = projectSessionTimeline(
            listOf(tool("shell", "bash", ToolArguments(strings = mapOf("command" to "git status")), result = "working tree clean")),
        ).single() as SessionDisplayItem.ActivityGroup

        assertEquals(ActivityStage.Execute, group.stage)
        assertEquals(ActivityStatus.Succeeded, group.status)
        assertEquals("git status", group.summary)
        assertTrue(group.operations.single().details.contains("command: git status"))
        assertTrue(group.operations.single().details.contains("working tree clean"))
    }

    @Test fun partialToolEventsDoNotDisappearOrGetAnInventedCompletionStatus() {
        val partial = message("partial", "tool", "Tool started")
        val projected = projectSessionTimeline(listOf(partial)).single() as SessionDisplayItem.Raw
        assertEquals(partial, projected.item)
    }

    @Test fun delegatedAndEvaluatedWorkIsRetained() {
        val projected = projectSessionTimeline(
            listOf(
                tool("task", "task", ToolArguments(strings = mapOf("i" to "Investigating session state")), completed = false),
                tool("eval", "functions.eval", ToolArguments(strings = mapOf("code" to "print(42)")), result = "42"),
            ),
        )
        val task = projected[0] as SessionDisplayItem.ActivityGroup
        val eval = projected[1] as SessionDisplayItem.ActivityGroup
        assertEquals(ActivityStatus.Running, task.status)
        assertEquals(ActivityStage.Execute, eval.stage)
        assertEquals("print(42)", eval.summary)
        assertTrue(eval.operations.single().details.contains("42"))
    }

    private fun arguments(
        path: String,
        old: String? = null,
        new: String? = null,
        content: String? = null,
    ): ToolArguments {
        val json = JSONObject().put("path", path)
        old?.let { json.put("old", it) }
        new?.let { json.put("new", it) }
        content?.let { json.put("content", it) }
        return ToolArguments.parse(json.toString())
    }
}
