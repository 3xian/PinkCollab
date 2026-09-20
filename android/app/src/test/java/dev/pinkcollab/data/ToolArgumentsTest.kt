package dev.pinkcollab.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentsTest {
    @Test fun argumentsThatAreNotJsonObjectsCarryNoValues() {
        // A missing call payload reaches the client as JSON null and must not need the UI to know.
        assertEquals(ToolArguments(), ToolArguments.parse("null"))
        assertEquals(ToolArguments(), ToolArguments.parse(""))
        assertEquals(ToolArguments(), ToolArguments.parse("not json"))
    }

    @Test fun arrayEntriesThatAreNotStringsDoNotHideTheOnesThatAre() {
        val arguments = ToolArguments.parse("""{"files":["src/A.kt",{"path":"src/B.kt"},7],"path":"src/C.kt"}""")
        assertEquals(listOf("src/A.kt"), arguments.stringLists["files"])
        assertEquals("src/C.kt", arguments.strings["path"])
        assertTrue(arguments.raw.isNotBlank())
    }

    @Test fun stringArgumentsFromOlderGatewaysStillParse() {
        val arguments = ToolArguments.parse("""{"path":"src/Login.kt","old_string":"a","new_string":"b"}""")
        assertEquals("src/Login.kt", arguments.strings["path"])
        assertEquals("a", arguments.strings["old_string"])
        assertEquals("b", arguments.strings["new_string"])
    }

    @Test fun valuesThatAreNotStringsStayVisibleOnlyThroughRaw() {
        val arguments = ToolArguments.parse("""{"timeout":30,"force":true,"nested":{"a":1}}""")
        assertTrue(arguments.strings.isEmpty())
        assertTrue(arguments.stringLists.isEmpty())
        assertTrue(arguments.raw.contains("timeout"))
    }
}
