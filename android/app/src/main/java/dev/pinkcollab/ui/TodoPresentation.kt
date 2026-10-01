package dev.pinkcollab.ui

import dev.pinkcollab.data.TimelineItem
import dev.pinkcollab.data.TodoPhase
import dev.pinkcollab.data.ToolTrace

internal data class TodoPlan(val phases: List<TodoPhase>, val trace: ToolTrace)

internal sealed interface SessionTodo {
    data object Absent : SessionTodo
    data object Unavailable : SessionTodo
    data class Snapshot(val plan: TodoPlan) : SessionTodo
}

internal fun ToolTrace.isTodoSnapshot(): Boolean =
    toolIdentity(name).name == "todo" && completed && !isError

/** The newest successful call is authoritative, even when its snapshot is unavailable. */
internal fun projectSessionTodo(timeline: List<TimelineItem>): SessionTodo {
    val trace = timeline.lastOrNull { it.kind == "tool" && it.tool?.isTodoSnapshot() == true }?.tool
        ?: return SessionTodo.Absent
    val phases = trace.todoPhases ?: return SessionTodo.Unavailable
    return SessionTodo.Snapshot(TodoPlan(phases, trace))
}

/** Empty and unavailable live snapshots both suppress the saved plan. */
internal fun latestSessionTodo(saved: SessionTodo, live: SessionTodo): TodoPlan? {
    val latest = if (live == SessionTodo.Absent) saved else live
    return (latest as? SessionTodo.Snapshot)?.plan
        ?.takeIf { it.phases.any { phase -> phase.tasks.isNotEmpty() } }
}
