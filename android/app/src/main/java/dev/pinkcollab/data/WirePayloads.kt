package dev.pinkcollab.data

import org.json.JSONObject

/** Read the response envelope once; ToolTrace resolves references directly into typed plans. */
internal fun readTodoPlans(value: JSONObject, previous: Map<String, List<TodoPhase>> = emptyMap()): Map<String, List<TodoPhase>> {
    val dictionary = value.optJSONObject("todoPlans") ?: return previous
    return previous + dictionary.keys().asSequence().associateWith { version ->
        parseTodoPhases(dictionary.getJSONArray(version)) ?: error("Invalid Todo payload: $version")
    }
}

internal fun Session.wireRecord() = JSONObject().put("id", id).put("hostId", hostId).put("cwd", cwd).put("title", title)
    .put("createdAt", createdAt).put("updatedAt", updatedAt).put("origin", if(origin == SessionOrigin.Discovered) "discovered" else "managed")
