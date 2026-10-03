package dev.pinkcollab.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WirePayloadsTest {
    @Test fun references_resolve_without_rewriting_the_response() {
        val page = JSONObject("""{"items":[{"id":"call","kind":"tool","text":"Finished","timestamp":"","tool":{"todoRef":"plan"}}],"todoPlans":{"plan":[{"name":"ship","tasks":[{"content":"verify","status":"pending"}]}]},"other":{"todoRef":"unrelated"}}""")
        val original = page.toString()
        val ready = historyPageState(page)
        assertEquals("verify", ready.items.single().tool!!.todoPhases!!.single().tasks.single().content)
        assertEquals(original, page.toString())
        val plans = readTodoPlans(page)
        val item = page.getJSONArray("items").getJSONObject(0).item(plans)
        assertSame(plans.getValue("plan"), item.tool!!.todoPhases)
    }

    @Test fun missing_reference_fails_at_the_tool_boundary() {
        val item = JSONObject("""{"id":"call","kind":"tool","text":"Finished","timestamp":"","tool":{"todoRef":"missing"}}""")
        assertThrows(IllegalStateException::class.java) { item.item() }
    }
}
