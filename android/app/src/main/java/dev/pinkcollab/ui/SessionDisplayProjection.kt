package dev.pinkcollab.ui

import dev.pinkcollab.data.TimelineItem
import dev.pinkcollab.data.ToolTrace

enum class SessionDisplayMode { Concise, Debug }

enum class ActivityStage { Explore, Change, Execute, Other }

enum class ActivityStatus { Running, Succeeded, Failed }

data class ActivityOperation(
    val id: String,
    val name: String,
    val action: String,
    val status: ActivityStatus,
    val callId: String = id,
    val error: String = "",
    val targets: List<String> = emptyList(),
    val preview: String = "",
)

sealed interface SessionDisplayItem {
    val id: String

    data class Message(
        override val id: String,
        val role: String,
        val text: String,
        val timestamp: String,
    ) : SessionDisplayItem

    data class ActivityGroup(
        override val id: String,
        val summary: String,
        val operations: List<ActivityOperation>,
    ) : SessionDisplayItem

    data class Error(
        override val id: String,
        val text: String,
        val details: String = "",
    ) : SessionDisplayItem

    data class Feedback(
        override val id: String,
        val question: String,
        val answer: String,
        val timestamp: String,
    ) : SessionDisplayItem

    data class Raw(override val id: String, val item: TimelineItem) : SessionDisplayItem
}

/**
 * Converts the lossless wire timeline into the intentionally small model used by the phone UI.
 * Keeping this policy out of Compose also makes a future Detailed mode independent from storage.
 */
fun projectSessionTimeline(
    timeline: List<TimelineItem>,
    mode: SessionDisplayMode = SessionDisplayMode.Concise,
): List<SessionDisplayItem> {
    if (mode == SessionDisplayMode.Debug) {
        return timeline.map { item ->
            SessionDisplayItem.Raw("raw:${item.id}", item)
        }
    }

    val output = mutableListOf<SessionDisplayItem>()
    var group: MutableActivityGroup? = null

    fun flushGroup() {
        group?.let { output += it.build() }
        group = null
    }

    timeline.forEach { item ->
        if (item.kind == "tool") {
            val trace = item.tool
            if (trace != null) {
                // Structured plans belong to the pinned panel and do not split activity groups.
                if (trace.isTodoSnapshot() && trace.todoPhases != null) return@forEach
                if (group == null) group = MutableActivityGroup("activity:${item.id}")
                group?.add(trace)
            } else {
                // Older or partial tool events still belong in the timeline; do not invent status.
                flushGroup()
                output += SessionDisplayItem.Raw("tool:${item.id}", item)
            }
            return@forEach
        }

        when (item.kind) {
            "user", "assistant" -> if (item.text.isNotBlank()) {
                // A new user-readable message is a hard boundary between work phases.
                flushGroup()
                output += SessionDisplayItem.Message(item.id, item.kind, item.text.trim(), item.timestamp)
            }
            "error" -> {
                flushGroup()
                output += SessionDisplayItem.Error(item.id, conciseError(item.text), item.detail)
            }
            "feedback" -> {
                flushGroup()
                output += SessionDisplayItem.Feedback(item.id, item.detail, item.text, item.timestamp)
            }
            // Thinking, compaction, subagent bookkeeping, metadata and successful raw results
            // intentionally have no concise representation and do not split a visible group.
        }
    }
    flushGroup()
    return output
}

private class MutableActivityGroup(
    private val id: String,
) {
    private val traces = mutableListOf<ToolTrace>()

    fun add(trace: ToolTrace) {
        traces += trace
    }

    fun build(): SessionDisplayItem.ActivityGroup {
        val operations = traces.map { trace ->
            ActivityOperation(
                id = trace.callId,
                name = trace.name,
                action = operationAction(trace),
                status = operationStatus(trace),
                callId = trace.callId,
                error = if (trace.isError) trace.summary?.error?.takeIf(String::isNotBlank)
                    ?: conciseError(trace.result) else "",
                targets = operationTargets(trace),
                preview = (trace.summary?.preview?.takeIf(String::isNotBlank) ?: trace.result).lineSequence().filter(String::isNotBlank).take(3).map { it.take(360) }
                    .joinToString("\n").take(360),
            )
        }
        return SessionDisplayItem.ActivityGroup(
            id = id,
            summary = operations.lastOrNull { it.error.isNotBlank() }?.error.orEmpty(),
            operations = operations,
        )
    }
}

private fun operationTargets(trace: ToolTrace): List<String> {
    val summary = trace.summary
    val supplied = (listOfNotNull(summary?.target?.takeIf(String::isNotBlank)) + summary?.files.orEmpty())
        .filter(String::isNotBlank).distinct()
    if (supplied.isNotEmpty()) return supplied
    return listOf("path", "file", "filePath", "file_path", "filename", "query", "command", "url")
        .mapNotNull { trace.arguments.strings[it]?.takeIf(String::isNotBlank) }.distinct().take(4)
}

/** Counts observed calls, without interpreting a successful command as task completion. */
internal fun activityGroupSummary(operations: List<ActivityOperation>): String {
    val counts = operations.groupingBy {
        val identity = toolIdentity(it.name)
        when (identity.activityStage) {
            ActivityStage.Explore -> if (identity.workFamily == ToolFamily.Search ||
                identity.name.startsWith("find_") || identity.name.startsWith("glob_")) "search" else "read"
            ActivityStage.Change -> "change"
            ActivityStage.Execute -> "command"
            ActivityStage.Other -> "tool call"
        }
    }.eachCount()
    return listOf("search", "read", "change", "command", "tool call").mapNotNull { noun ->
        val count = counts[noun] ?: return@mapNotNull null
        "$count $noun${if (count == 1) "" else "s"}"
    }.joinToString(" · ")
}

private fun operationStatus(trace: ToolTrace): ActivityStatus = when {
    !trace.completed -> ActivityStatus.Running
    trace.isError -> ActivityStatus.Failed
    else -> ActivityStatus.Succeeded
}

private fun operationAction(trace: ToolTrace): String =
    listOf("i", "description", "title").firstNotNullOfOrNull { key ->
        trace.arguments.strings[key]?.takeIf(String::isNotBlank)
    } ?: trace.summary?.action?.takeIf(String::isNotBlank) ?: when (toolIdentity(trace.name).family) {
        ToolFamily.Read -> "Read"
        ToolFamily.Search -> "Search"
        ToolFamily.Edit -> "Edit"
        ToolFamily.Write -> "Write"
        ToolFamily.Command -> "Run command"
        ToolFamily.Code -> "Execute code"
        ToolFamily.Delegate -> "Delegate work"
        ToolFamily.Wait -> "Wait for work"
        else -> "Tool activity"
    }

private fun conciseResult(value: String): String = value
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .lastOrNull()
    .orEmpty()
    .take(180)

private fun conciseError(value: String): String = conciseResult(value).ifBlank { "Failed" }
