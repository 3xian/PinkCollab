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
}
