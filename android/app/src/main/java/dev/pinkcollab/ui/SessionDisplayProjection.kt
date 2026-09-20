package dev.pinkcollab.ui

import dev.pinkcollab.data.TimelineItem
import dev.pinkcollab.data.ToolTrace

enum class SessionDisplayMode { Concise, Debug }

enum class ActivityStage { Explore, Change, Execute }

enum class ActivityStatus { Running, Succeeded, Failed }

enum class ActivityDetailKind { Diff, Content, Changes, Error }

sealed interface SessionDisplayItem {
    val id: String

    data class Message(
        override val id: String,
        val role: String,
        val text: String,
    ) : SessionDisplayItem

    data class ActivityGroup(
        override val id: String,
        val stage: ActivityStage,
        val operationCount: Int,
        val files: List<String>,
        val status: ActivityStatus,
        val summary: String,
        val details: String,
        val detailKind: ActivityDetailKind?,
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
        return timeline.mapIndexed { index, item ->
            SessionDisplayItem.Raw("raw:${item.id}:$index", item)
        }
    }

    val output = mutableListOf<SessionDisplayItem>()
    var group: MutableActivityGroup? = null

    fun flushGroup() {
        group?.let { output += it.build() }
        group = null
    }

    timeline.forEachIndexed { index, item ->
        if (item.kind == "tool") {
            val trace = item.tool
            val stage = trace?.let(::activityStage)
            if (trace != null && stage != null) {
                if (group?.stage != stage) {
                    flushGroup()
                    group = MutableActivityGroup(stage, "activity:${item.id}:$index")
                }
                group?.add(trace)
            } else if (trace?.isError == true || item.text.contains("failed", ignoreCase = true)) {
                flushGroup()
                output += SessionDisplayItem.Error(
                    id = "error:${item.id}:$index",
                    text = conciseError(trace?.result.orEmpty().ifBlank { item.text }),
                    details = trace?.result.orEmpty().ifBlank { item.detail },
                )
            }
            return@forEachIndexed
        }

        when (item.kind) {
            "user", "assistant" -> if (item.text.isNotBlank()) {
                // A new user-readable message is a hard boundary between work phases.
                flushGroup()
                output += SessionDisplayItem.Message(item.id, item.kind, item.text.trim())
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
        // Change groups scan each trace once: the same result feeds the file list and the details.
        val scanned = if (stage == ActivityStage.Change) {
            traces.map { trace -> trace to extractFiles(trace) }
        } else {
            emptyList()
        }
        val files = scanned.flatMap { it.second }.distinct()
        val failed = traces.any(ToolTrace::isError)
        val status = when {
            failed -> ActivityStatus.Failed
            traces.all(ToolTrace::completed) -> ActivityStatus.Succeeded
            else -> ActivityStatus.Running
        }
        val summary = when (stage) {
            ActivityStage.Explore -> ""
            ActivityStage.Change -> files.joinToString(" · ")
            ActivityStage.Execute -> (if (failed) traces.filter(ToolTrace::isError) else traces).asReversed()
                .asSequence()
                .map { conciseResult(it.result) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        }
        // One decision: a group carries details exactly when it has a detail kind, and the card only
        // offers the expander when it does.
        val details = when {
            stage == ActivityStage.Change -> changeDetails(scanned)
            stage == ActivityStage.Execute && failed -> traces
                .filter(ToolTrace::isError)
                .joinToString("\n\n", transform = ::failureDetails)
                .takeIf(String::isNotBlank)
                ?.let { GroupDetails(it, ActivityDetailKind.Error) }
            else -> null
        }
        return SessionDisplayItem.ActivityGroup(
            id = id,
            stage = stage,
            operationCount = traces.size,
            files = files,
            status = status,
            summary = summary,
            details = details?.text.orEmpty(),
            detailKind = details?.kind,
        )
    }
}

private data class GroupDetails(val text: String, val kind: ActivityDetailKind)

private fun activityStage(trace: ToolTrace): ActivityStage? {
    val name = trace.name.lowercase().substringAfterLast('.').substringAfterLast('/')
    return when {
        name in setOf("read", "grep", "find", "lsp") ||
            listOf("read_", "grep_", "find_", "lsp_").any(name::startsWith) -> ActivityStage.Explore
        name in setOf("edit", "write", "ast_edit") ||
            listOf("edit_", "write_", "ast_edit_").any(name::startsWith) -> ActivityStage.Change
        name in setOf("bash", "test", "python") ||
            listOf("bash_", "test_", "python_").any(name::startsWith) -> ActivityStage.Execute
        else -> null
    }
}

private val patchPath = Regex("""(?m)^(?:\+\+\+\s+b/|---\s+a/|\*\*\* (?:Update|Add|Delete) File:\s*)([^\r\n]+)""")

private fun extractFiles(trace: ToolTrace): List<String> {
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

private fun changeDetails(scanned: List<Pair<ToolTrace, List<String>>>): GroupDetails? {
    val entries = scanned.mapNotNull { (trace, paths) -> changeDetail(trace, paths) }
    if (entries.isEmpty()) return null
    return GroupDetails(
        entries.joinToString("\n\n") { it.text },
        entries.map(ChangeDetails::kind).distinct().singleOrNull() ?: ActivityDetailKind.Changes,
    )
}

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

private fun failureDetails(trace: ToolTrace): String = buildList {
    if (trace.arguments.raw.isNotBlank()) add(trace.arguments.raw)
    if (trace.result.isNotBlank()) add(trace.result)
}.joinToString("\n")

private fun conciseResult(value: String): String = value
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .lastOrNull()
    .orEmpty()
    .take(180)

private fun conciseError(value: String): String = conciseResult(value).ifBlank { "Failed" }
