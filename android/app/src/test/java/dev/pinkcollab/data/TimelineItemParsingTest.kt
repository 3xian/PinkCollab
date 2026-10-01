package dev.pinkcollab.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineItemParsingTest {
    @Test fun argumentsSentAsAStringByAnOlderGatewayStillParse() {
        val item = JSONObject(
            """{"id":"call-1","kind":"tool","text":"Finished · edit","detail":"",
                "timestamp":"2026-09-20T00:00:00Z",
                "tool":{"callId":"call-1","name":"edit","arguments":"{\"path\":\"src/Login.kt\"}",
                "result":"updated","isError":false,"completed":true}}"""
        ).item()

        assertEquals("src/Login.kt", item.tool!!.arguments.strings["path"])
        assertEquals("updated", item.tool!!.result)
    }

    @Test fun aToolWithoutArgumentsHasNoValues() {
        val item = JSONObject(
            """{"id":"call-1","kind":"tool","text":"Running · edit","detail":"",
                "timestamp":"2026-09-20T00:00:00Z",
                "tool":{"callId":"call-1","name":"edit","arguments":null,"completed":false}}"""
        ).item()

        assertEquals(ToolArguments(), item.tool!!.arguments)
    }
    private fun todoItem(phases: String): TimelineItem = JSONObject(
        """{"id":"plan","kind":"tool","text":"Finished · todo","timestamp":"",
            "tool":{"name":"todo","completed":true,"todoPhases":$phases}}"""
    ).item()

    @Test fun structuredTodoKeepsLiteralSuffixesAndExplicitStatuses() {
        val item = todoItem("""[{"name":"Ship","tasks":[
            {"content":"Verify (dropped)","status":"pending"},
            {"content":"Check (blocked)","status":"in_progress"},
            {"content":"Review","status":"blocked","blocker":"Waiting (for access)"},
            {"content":"Done","status":"completed"},
            {"content":"Old","status":"abandoned"}]}]""")
        val tasks = item.tool!!.todoPhases!!.single().tasks
        assertEquals("Verify (dropped)", tasks[0].content)
        assertEquals(listOf(TodoStatus.Pending, TodoStatus.Active, TodoStatus.Blocked,
            TodoStatus.Completed, TodoStatus.Abandoned), tasks.map { it.status })
        assertEquals("Waiting (for access)", tasks[2].blocker)
    }

    @Test fun malformedOrFutureTodoSnapshotsAreUnavailableAndClearIsExplicit() {
        assertEquals(null, todoItem("null").tool!!.todoPhases)
        assertEquals(null, todoItem("""[{"name":"Ship","tasks":[{"content":"Check","status":"future"}]}]""").tool!!.todoPhases)
        assertEquals(null, todoItem("""[{"name":"Ship","tasks":[{"status":"pending"}]}]""").tool!!.todoPhases)
        assertEquals(emptyList<TodoPhase>(), todoItem("[]").tool!!.todoPhases)
    }

}
