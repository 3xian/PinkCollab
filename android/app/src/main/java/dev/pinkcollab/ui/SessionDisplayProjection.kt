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
    val target: String,
    val status: ActivityStatus,
    val details: String,
    val detailKind: ActivityDetailKind?,
    val callId: String = id,
    val detailsVersion: String = "",
    val detailsAvailable: Boolean = false,
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
        val files: List<String>,
        val status: ActivityStatus,
        val action: String,
        val summary: String,
        val failureCount: Int,
        val operations: List<ActivityOperation>,
    ) : SessionDisplayItem

    data class Error(
        override val id: String,
        val text: String,
        val details: String = "",
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
        val scanned = traces.map { trace ->
            trace to if (stage == ActivityStage.Change) extractFiles(trace) else emptyList()
        }
        val files = scanned.flatMap { it.second }.distinct()
        val operations = scanned.map { (trace, paths) ->
            val change = if (stage == ActivityStage.Change) changeDetail(trace, paths) else null
            val details = operationDetails(trace, change)
            ActivityOperation(
                id = trace.callId,
                name = trace.name,
                action = operationAction(trace),
                target = operationTarget(trace),
                status = operationStatus(trace),
                details = details,
                detailKind = if (trace.detailsAvailable) ActivityDetailKind.Operation else if (details.isBlank()) null else change?.kind
                    ?: if (trace.isError) ActivityDetailKind.Error else ActivityDetailKind.Operation,
                callId = trace.callId,
                detailsVersion = trace.detailsVersion,
                detailsAvailable = trace.detailsAvailable,
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
            files = files,
            status = status,
            action = focus.action,
            summary = focus.target.ifBlank { files.joinToString(", ") },
            failureCount = operations.count { it.status == ActivityStatus.Failed },
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
    trace.summary?.action?.takeIf(String::isNotBlank) ?: listOf("i", "description", "title").firstNotNullOfOrNull { key ->
        trace.arguments.strings[key]?.takeIf(String::isNotBlank)
    } ?: when (toolIdentity(trace.name).family) {
        ToolFamily.Read -> "Read"
        ToolFamily.Search -> "Search"
        ToolFamily.Edit -> "Edit"
        ToolFamily.Write -> "Write"
        ToolFamily.Command -> "Run command"
        ToolFamily.Code -> "Execute code"
        ToolFamily.Delegate -> "Delegate work"
        ToolFamily.Wait -> "Wait for work"
        else -> trace.name.ifBlank { "Tool activity" }
    }

private fun operationTarget(trace: ToolTrace): String {
    trace.summary?.let { return listOf(it.target, it.error).filter(String::isNotBlank).joinToString(" · ") }
    val args = trace.arguments
    val target = listOf("path", "file", "filePath", "file_path", "filename", "url", "uri", "cwd")
        .firstNotNullOfOrNull { args.strings[it]?.takeIf(String::isNotBlank) }
        ?: listOf("files", "paths").firstNotNullOfOrNull { args.stringLists[it]?.takeIf(List<String>::isNotEmpty)?.joinToString(", ") }
    val subject = listOf("command", "cmd", "query", "pattern", "task", "code")
        .firstNotNullOfOrNull { args.strings[it]?.takeIf(String::isNotBlank) }
    return listOfNotNull(subject, target).distinct().joinToString(", ")
}

private val patchPath = Regex("""(?m)^(?:\+\+\+\s+b/|---\s+a/|\*\*\* (?:Update|Add|Delete) File:\s*)([^\r\n]+)""")

private fun extractFiles(trace: ToolTrace): List<String> {
    trace.summary?.let { return it.files }
    val arguments = trace.arguments
    val direct = listOf("path", "file", "filePath", "file_path", "filename")
        .mapNotNull(arguments.strings::get)
    val listed = listOf("files", "paths").flatMap { arguments.stringLists[it].orEmpty() }
    val patches = listOfNotNull(
        arguments.strings["patch"],
        arguments.strings["diff"],
        trace.result,
    ).flatMap { value -> patchPath.findAll(value).map { it.groupValues[1] }.toList() }
    return (direct + listed + patches)
        .map { it.trim().removeSurrounding("\"") }
        .filter { it.isNotBlank() && it != "/dev/null" && "://" !in it }
}

private data class ChangeDetails(val text: String, val kind: ActivityDetailKind)


private fun changeDetail(trace: ToolTrace, paths: List<String>): ChangeDetails? {
    val arguments = trace.arguments
    val path = paths.firstOrNull()
    val old = arguments.strings["old_string"] ?: arguments.strings["old"]
    val new = arguments.strings["new_string"] ?: arguments.strings["new"]
    val content = arguments.strings["content"]
    val patch = arguments.strings["patch"] ?: arguments.strings["diff"]
    val detail = when {
        !patch.isNullOrBlank() -> ChangeDetails(patch, ActivityDetailKind.Diff)
        path != null && old != null && new != null -> ChangeDetails(fileDiff(path, old, new), ActivityDetailKind.Diff)
        content != null -> ChangeDetails(listOfNotNull(path, content).joinToString("\n\n"), ActivityDetailKind.Content)
        else -> ChangeDetails(trace.result, ActivityDetailKind.Changes)
    }
    // Blank details would leave a detail kind without anything to expand, so they collapse to null.
    return detail.takeIf { it.text.isNotBlank() }
}

private fun fileDiff(path: String, old: String, new: String): String = buildString {
    appendLine("--- a/$path")
    appendLine("+++ b/$path")
    appendLine("@@")
    old.lineSequence().forEach { appendLine("-$it") }
    new.lineSequence().forEach { appendLine("+$it") }
}.trimEnd()

private fun operationDetails(trace: ToolTrace, change: ChangeDetails?): String = buildList {
    if (change != null) {
        add(change.text)
    } else {
        val arguments = trace.arguments.raw.ifBlank {
            (trace.arguments.strings.map { (key, value) -> "$key: $value" } +
                trace.arguments.stringLists.map { (key, value) -> "$key: ${value.joinToString()}" }).joinToString("\n")
        }
        if (arguments.isNotBlank() && arguments != "{}") add("Arguments\n$arguments")
    }
    if (trace.result.isNotBlank() && trace.result != change?.text) add("Output\n${trace.result}")
}.joinToString("\n\n")

private fun conciseResult(value: String): String = value
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .lastOrNull()
    .orEmpty()
    .take(180)

private fun conciseError(value: String): String = conciseResult(value).ifBlank { "Failed" }
