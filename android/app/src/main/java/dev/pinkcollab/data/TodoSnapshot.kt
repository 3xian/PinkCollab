package dev.pinkcollab.data

import org.json.JSONArray
import org.json.JSONObject

enum class TodoStatus(val label: String) {
    Pending("Queued"), Active("In progress"), Completed("Completed"),
    Abandoned("Dropped"), Blocked("Blocked"),
}

data class TodoTask(val content: String, val status: TodoStatus, val blocker: String = "")
data class TodoPhase(val name: String, val tasks: List<TodoTask>)

/** Invalid or future wire shapes stay unavailable rather than inventing task states. */
internal fun parseTodoPhases(value: JSONArray): List<TodoPhase>? = runCatching {
    value.objects().map { phase ->
        TodoPhase(phase.get("name") as String, phase.getJSONArray("tasks").objects().map { task ->
            val status = when (task.getString("status")) {
                "pending" -> TodoStatus.Pending
                "in_progress" -> TodoStatus.Active
                "completed" -> TodoStatus.Completed
                "abandoned" -> TodoStatus.Abandoned
                "blocked" -> TodoStatus.Blocked
                else -> error("Unsupported Todo status")
            }
            val blocker = task.opt("blocker")
            TodoTask(task.get("content") as String, status,
                if (blocker == null || blocker == JSONObject.NULL) "" else blocker as String)
        })
    }
}.getOrNull()
