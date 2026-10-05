package dev.pinkcollab.ui

import dev.pinkcollab.data.TimelineItem
import dev.pinkcollab.data.ToolTrace

enum class SessionDisplayMode { Concise, Debug }

enum class ActivityStage { Explore, Change, Execute, Other }

enum class ActivityStatus { Running, Succeeded, Failed }

enum class ActivityDetailKind { Diff, Content, Changes, Error, Operation }

data class ActivityOperation(
    val id: String,
    val name: String,
    val action: String,
    val status: ActivityStatus,
    val details: String,
    val detailKind: ActivityDetailKind?,
    val callId: String = id,
    val detailsVersion: String = "",
    val detailsAvailable: Boolean = false,
    val error: String = "",
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
        val stage: ActivityStage,
        val operationCount: Int,
        val status: ActivityStatus,
        val action: String,
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
                val stage = toolIdentity(trace.name).activityStage
                if (group?.stage != stage) {
                    flushGroup()
                    group = MutableActivityGroup(stage, "activity:${item.id}")
                }
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
    val stage: ActivityStage,
    private val id: String,
) {
    private val traces = mutableListOf<ToolTrace>()

    fun add(trace: ToolTrace) {
        traces += trace
    }

    fun build(): SessionDisplayItem.ActivityGroup {
        val operations = traces.map { trace ->
            val change = if (stage == ActivityStage.Change) changeDetail(trace) else null
            val details = operationDetails(trace, change)
            ActivityOperation(
                id = trace.callId,
                name = trace.name,
                action = operationAction(trace),
                status = operationStatus(trace),
                details = details,
                detailKind = if (trace.detailsAvailable) ActivityDetailKind.Operation else if (details.isBlank()) null else change?.kind
                    ?: if (trace.isError) ActivityDetailKind.Error else ActivityDetailKind.Operation,
                callId = trace.callId,
                detailsVersion = trace.detailsVersion,
                detailsAvailable = trace.detailsAvailable,
                error = if (trace.isError) trace.summary?.error?.takeIf(String::isNotBlank)
                    ?: conciseError(trace.result) else "",
            )
        }
        val status = when {
            operations.any { it.status == ActivityStatus.Running } -> ActivityStatus.Running
            operations.any { it.status == ActivityStatus.Failed } -> ActivityStatus.Failed
            else -> ActivityStatus.Succeeded
        }
        val focus = operations.lastOrNull { it.status == ActivityStatus.Running }
            ?: operations.lastOrNull { it.status == ActivityStatus.Failed }
            ?: operations.last()
        return SessionDisplayItem.ActivityGroup(
            id = id,
            stage = stage,
            operationCount = operations.size,
            status = status,
            action = focus.action,
            summary = operations.lastOrNull { it.error.isNotBlank() }?.error.orEmpty(),
            operations = operations,
        )
    }
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


private data class ChangeDetails(val text: String, val kind: ActivityDetailKind)

private fun changeDetail(trace: ToolTrace): ChangeDetails {
    val arguments = trace.arguments
    val old = arguments.strings["old_string"] ?: arguments.strings["old"]
    val new = arguments.strings["new_string"] ?: arguments.strings["new"]
    val patch = arguments.strings["patch"] ?: arguments.strings["diff"]
    if (!patch.isNullOrBlank()) return ChangeDetails("", ActivityDetailKind.Diff)
    if (old != null && new != null) {
        val path = listOf("path", "file", "filePath", "file_path", "filename")
            .firstNotNullOfOrNull { arguments.strings[it]?.takeIf(String::isNotBlank) }
            ?: listOf("files", "paths").firstNotNullOfOrNull { arguments.stringLists[it]?.firstOrNull() }
        if (path != null) return ChangeDetails(fileDiff(path, old, new), ActivityDetailKind.Diff)
    }
    return ChangeDetails(
        "",
        if (arguments.strings["content"] != null) ActivityDetailKind.Content else ActivityDetailKind.Changes,
    )
}

private fun fileDiff(path: String, old: String, new: String): String = buildString {
    appendLine("--- a/$path")
    appendLine("+++ b/$path")
    appendLine("@@")
    old.lineSequence().forEach { appendLine("-$it") }
    new.lineSequence().forEach { appendLine("+$it") }
}.trimEnd()

private fun operationDetails(trace: ToolTrace, change: ChangeDetails?): String = buildList {
    val arguments = trace.arguments.raw.ifBlank {
        (trace.arguments.strings.map { (key, value) -> "$key: $value" } +
            trace.arguments.stringLists.map { (key, value) -> "$key: ${value.joinToString()}" }).joinToString("\n")
    }
    if (arguments.isNotBlank() && arguments != "{}") add("Arguments\n$arguments")
    if (change != null && change.text.isNotBlank()) add(change.text)
    if (trace.result.isNotBlank()) add("Output\n${trace.result}")
}.joinToString("\n\n")

private fun conciseResult(value: String): String = value
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .lastOrNull()
    .orEmpty()
    .take(180)

private fun conciseError(value: String): String = conciseResult(value).ifBlank { "Failed" }
