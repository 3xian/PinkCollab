package dev.pinkcollab.ui

import dev.pinkcollab.data.TimelineItem
import dev.pinkcollab.data.ToolArguments
import dev.pinkcollab.data.ToolTrace
import dev.pinkcollab.data.ToolSummary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDisplayProjectionTest {
    @Test fun command_output_remains_visible_without_becoming_an_assistant_message() {
        val output = TimelineItem("command", "system", "Compaction complete", "command output", "")
        assertEquals(listOf(SessionDisplayItem.Raw(output.id, output)), projectSessionTimeline(listOf(output)))
    }

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

    @Test fun conciseProjectionKeepsMixedToolsTogetherUntilAssistantText() {
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

        assertEquals(4, projected.size)
        val work = projected[1] as SessionDisplayItem.ActivityGroup
        assertEquals(listOf("r", "g", "e1", "e2"), work.operations.map { it.id })
        assertEquals("2026-09-20T00:00:00Z", (projected[2] as SessionDisplayItem.Message).timestamp)
        val execute = projected[3] as SessionDisplayItem.ActivityGroup
        assertEquals(ActivityStatus.Succeeded, execute.operations.single().status)
        assertEquals("3 tests passed", execute.operations.single().preview)
    }

    @Test fun feedbackSeparatesToolGroupsEvenWhenTheAnswerIsEmpty() {
        val projected = projectSessionTimeline(listOf(
            tool("before", "read"),
            TimelineItem("response", "feedback", "", "Additional instructions?", "2026-10-05T10:00:00Z"),
            tool("after", "read"),
        ))

        assertEquals(3, projected.size)
        assertEquals(listOf("before"), (projected[0] as SessionDisplayItem.ActivityGroup).operations.map { it.id })
        assertTrue(projected[1] is SessionDisplayItem.Feedback)
        assertEquals(listOf("after"), (projected[2] as SessionDisplayItem.ActivityGroup).operations.map { it.id })
    }

    @Test fun unknownToolsRetainTheirOwnOperationAndErrorsRemainAccessible() {
        val projected = projectSessionTimeline(
            listOf(
                tool("metadata", "set_metadata", result = "saved"),
                tool("test", "test", result = "stack line\nAssertion failed", error = true),
            ),
        )

        val work = projected.single() as SessionDisplayItem.ActivityGroup
        assertEquals("set_metadata", work.operations[0].name)
        assertEquals(ActivityStatus.Succeeded, work.operations[0].status)
        assertEquals("saved", work.operations[0].preview)
        assertEquals(ActivityStatus.Failed, work.operations[1].status)
        assertEquals("Assertion failed", work.operations[1].error)
        assertEquals("Assertion failed", work.summary)
        assertEquals("stack line\nAssertion failed", work.operations[1].preview)
    }

    @Test fun debugProjectionPreservesEveryRawTimelineItem() {
        val raw = """{"path":"src/Login.kt","old":"before","new":"after","custom":{"enabled":true}}"""
        val source = listOf(
            message("n", "notice", "Compacting context"),
            tool("x", "edit", ToolArguments.parse(raw), result = "full output\nstack line", error = true),
            TimelineItem("failure", "error", "Failed", "Full error details", ""),
        )
        val projected = projectSessionTimeline(source, SessionDisplayMode.Debug)
        assertEquals(source.size, projected.size)
        assertTrue(projected.all { it is SessionDisplayItem.Raw })
        assertEquals(source, projected.map { (it as SessionDisplayItem.Raw).item })
    }

    @Test fun runningOperationDoesNotDiscardEarlierFailure() {
        val group = projectSessionTimeline(
            listOf(
                tool("failed", "bash", ToolArguments(strings = mapOf("command" to "false")), error = true),
                tool("running", "bash", ToolArguments(strings = mapOf("i" to "Reading repository state", "command" to "git status")), result = "", completed = false),
            ),
        ).single() as SessionDisplayItem.ActivityGroup

        assertEquals(group.operations.first().error, group.summary)
        assertTrue(group.summary.isNotBlank())
        assertEquals(listOf(ActivityStatus.Failed, ActivityStatus.Running), group.operations.map { it.status })
    }

    @Test fun explorationRetainsIntentQueryAndTargetWithoutNeedingOutput() {
        val group = projectSessionTimeline(
            listOf(
                tool("glob", "functions.glob", ToolArguments(strings = mapOf("path" to "src/**/*.kt")), result = ""),
                tool("search", "web_search", ToolArguments(strings = mapOf("description" to "Finding API documentation", "query" to "Compose state", "url" to "https://developer.android.com")), result = "documentation"),
            ),
        ).single() as SessionDisplayItem.ActivityGroup

        assertEquals("Finding API documentation", group.operations[1].action)
        assertTrue(group.summary.isBlank())
        assertEquals(listOf("Compose state", "https://developer.android.com"), group.operations[1].targets)
        assertEquals(listOf("src/**/*.kt"), group.operations[0].targets)
        assertEquals("documentation", group.operations[1].preview)
    }

    @Test fun successfulCommandsKeepCommandAndOutputRatherThanInferringVerification() {
        val group = projectSessionTimeline(
            listOf(tool("shell", "bash", ToolArguments(strings = mapOf("command" to "git status")), result = "working tree clean")),
        ).single() as SessionDisplayItem.ActivityGroup

        assertEquals(ActivityStatus.Succeeded, group.operations.single().status)
        assertTrue(group.summary.isBlank())
        assertEquals(listOf("git status"), group.operations.single().targets)
        assertEquals("working tree clean", group.operations.single().preview)
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
        val work = projected.single() as SessionDisplayItem.ActivityGroup
        assertEquals(ActivityStatus.Running, work.operations.first().status)
        assertEquals(listOf("task", "eval"), work.operations.map { it.id })
        assertEquals("42", work.operations[1].preview)
    }

    @Test fun allToolFamiliesKeepIntentTargetsAndPreviews() {
        for (name in listOf("read", "grep", "edit", "write", "bash", "eval", "task", "wait", "custom")) {
            val intent = "Inspecting the shared presentation"
            val raw = """{"i":"$intent","description":"Lower-priority description","title":"Lower-priority title","path":"src/Session.kt","command":"inspect --all","content":"replacement"}"""
            val group = projectSessionTimeline(listOf(tool(
                name, name, ToolArguments.parse(raw), result = "Supplied result",
            ))).single() as SessionDisplayItem.ActivityGroup
            val operation = group.operations.single()
            assertEquals(name, intent, operation.action)
            assertTrue(name, group.summary.isBlank())
            assertEquals(name, listOf("src/Session.kt", "inspect --all"), operation.targets)
            assertEquals(name, "Supplied result", operation.preview)
        }
    }

    @Test fun summaryOnlyFailuresStayVisibleWithoutFetchingFullDetails() {
        val trace = ToolTrace(
            "failed", "functions.read", ToolArguments(), "", true, true,
            summary = ToolSummary("Reading configuration", "/private/config", emptyList(), "Access denied"),
            detailsVersion = "version-2", detailsAvailable = true,
        )
        val group = projectSessionTimeline(listOf(
            tool("failed", trace.name).copy(tool = trace),
        )).single() as SessionDisplayItem.ActivityGroup
        val operation = group.operations.single()
        assertEquals(ActivityStatus.Failed, operation.status)
        assertEquals(trace.summary!!.action, operation.action)
        assertEquals(trace.summary.error, group.summary)
        assertEquals(trace.summary.error, operation.error)
    }

    @Test fun previewsUseBoundedSuppliedTargetsWithoutLoadingDetails() {
        val trace = ToolTrace("read", "read", ToolArguments(), "", false, true,
            summary = ToolSummary("Reading files", "src/App.kt", listOf("src/App.kt", "src/Ui.kt"), ""),
            detailsAvailable = true, detailsVersion = "v1")
        val operation = (projectSessionTimeline(listOf(tool("read", "read").copy(tool = trace)))
            .single() as SessionDisplayItem.ActivityGroup).operations.single()
        assertEquals(listOf("src/App.kt", "src/Ui.kt"), operation.targets)
        assertTrue(operation.preview.isEmpty())
    }

    @Test fun editSummaryProvidesFilenameAndPatchWithoutLoadingFullDetails() {
        val trace = ToolTrace("edit", "edit", ToolArguments(), "", false, true,
            summary = ToolSummary("Edit notes.md", "", listOf("/tmp/demo/notes.md"), "", "+DONE"),
            detailsAvailable = true, detailsVersion = "v2")
        val operation = (projectSessionTimeline(listOf(tool("edit", "edit").copy(tool = trace)))
            .single() as SessionDisplayItem.ActivityGroup).operations.single()
        assertEquals("Edit notes.md", operation.action)
        assertEquals(listOf("/tmp/demo/notes.md"), operation.targets)
        assertEquals("+DONE", operation.preview)
    }

    @Test fun inlinePreviewIsBoundedAndFullOutputRemainsAccessible() {
        val output = (1..100).joinToString("\n") { "Output line $it " + "x".repeat(200) }
        val source = listOf(tool("read", "read", result = output))
        val operation = (projectSessionTimeline(source)
            .single() as SessionDisplayItem.ActivityGroup).operations.single()
        assertTrue(operation.preview.length <= 360)
        assertTrue(operation.preview.lines().size <= 3)
        val raw = projectSessionTimeline(source, SessionDisplayMode.Debug).single() as SessionDisplayItem.Raw
        assertEquals(output, raw.item.tool!!.result)
    }

    @Test fun summaryCountsToolsWithoutImplyingVerificationOrHidingFailures() {
        val group = projectSessionTimeline(listOf(tool("r", "read"), tool("s", "search"),
            tool("e", "edit"), tool("b", "bash", error = true), tool("x", "custom", completed = false)))
            .single() as SessionDisplayItem.ActivityGroup
        assertEquals(5, group.operations.size)
        assertEquals("1 search · 1 read · 1 change · 1 command · 1 tool call", activityGroupSummary(group.operations))
        assertTrue(group.summary.isNotBlank())
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
