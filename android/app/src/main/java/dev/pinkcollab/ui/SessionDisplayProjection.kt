package dev.pinkcollab.ui

import dev.pinkcollab.data.TimelineItem
import dev.pinkcollab.data.ToolTrace

enum class SessionDisplayMode { Concise, Debug }

enum class ActivityStage { Explore, Change, Execute }

enum class ActivityStatus { Running, Succeeded, Failed }

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
            val trace = item.tool ?: legacyTrace(item)
            val stage = trace?.let(::activityStage)
            if (trace != null && stage != null) {
                if (group?.stage != stage) {
                    flushGroup()
                    group = MutableActivityGroup(stage, "activity:${item.id}:$index")
                }
                group?.add(trace, item.detail)
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
    private val rawDetails = mutableListOf<String>()

    fun add(trace: ToolTrace, legacyDetail: String) {
        traces += trace
        val detail = if (stage == ActivityStage.Change) {
            changeDiff(trace).ifBlank { legacyDetail }
        } else {
            buildList {
                if (trace.arguments.isNotBlank() && trace.arguments != "null") add(trace.arguments)
                if (trace.result.isNotBlank()) add(trace.result)
                if (isEmpty() && legacyDetail.isNotBlank()) add(legacyDetail)
            }.joinToString("\n")
        }
        if (detail.isNotBlank()) rawDetails += detail
    }

    fun build(): SessionDisplayItem.ActivityGroup {
        val files = if (stage == ActivityStage.Change) {
            traces.flatMap { extractFiles(it.arguments + "\n" + it.result) }.distinct()
        } else {
            emptyList()
        }
        val failed = traces.any { it.isError }
        val complete = traces.all { it.completed }
        val status = when {
            failed -> ActivityStatus.Failed
            complete -> ActivityStatus.Succeeded
            else -> ActivityStatus.Running
        }
        val summary = when (stage) {
            ActivityStage.Explore -> ""
            ActivityStage.Change -> files.joinToString(" · ")
            ActivityStage.Execute -> traces.asReversed()
                .asSequence()
                .map { conciseResult(it.result) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        }
        return SessionDisplayItem.ActivityGroup(
            id = id,
            stage = stage,
            operationCount = traces.size,
            files = files,
            status = status,
            summary = summary,
            details = rawDetails.joinToString("\n\n"),
        )
    }
}

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

private fun legacyTrace(item: TimelineItem): ToolTrace? {
    val name = item.text.substringAfterLast('·', "").trim()
    if (name.isBlank()) return null
    return ToolTrace(
        callId = item.id,
        name = name,
        arguments = if (item.text.startsWith("Finished") || item.text.startsWith("Tool failed")) "" else item.detail,
        result = if (item.text.startsWith("Finished") || item.text.startsWith("Tool failed")) item.detail else "",
        isError = item.text.startsWith("Tool failed"),
        completed = item.text.startsWith("Finished") || item.text.startsWith("Tool failed"),
    )
}

private val patchPath = Regex("""(?m)^(?:\+\+\+\s+b/|---\s+a/|\*\*\* (?:Update|Add|Delete) File:\s*)([^\r\n]+)""")

private fun extractFiles(value: String): List<String> =
    (listOf("path", "file", "filePath", "file_path", "filename").mapNotNull { jsonString(value, it) } +
        patchPath.findAll(value).map { it.groupValues[1] })
        .map { it.trim().removeSurrounding("\"") }
        .filter { it.isNotBlank() && it != "/dev/null" && "://" !in it }
        .toList()

private fun changeDiff(trace: ToolTrace): String {
    val arguments = trace.arguments
    val explicit = jsonString(arguments, "patch") ?: jsonString(arguments, "diff")
    if (!explicit.isNullOrBlank()) return explicit
    val path = extractFiles(arguments).firstOrNull() ?: return trace.result
    val old = jsonString(arguments, "old_string") ?: jsonString(arguments, "old")
    val new = jsonString(arguments, "new_string") ?: jsonString(arguments, "new")
        ?: jsonString(arguments, "content")
    if (old == null && new == null) return trace.result
    return buildString {
        appendLine("--- a/$path")
        appendLine("+++ b/$path")
        appendLine("@@")
        old?.lineSequence()?.forEach { appendLine("-$it") }
        new?.lineSequence()?.forEach { appendLine("+$it") }
    }.trimEnd()
}

private fun jsonString(value: String, key: String): String? {
    val match = Regex("""\"${Regex.escape(key)}\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""").find(value)
        ?: return null
    val escaped = match.groupValues[1]
    return buildString {
        var index = 0
        while (index < escaped.length) {
            val char = escaped[index++]
            if (char != '\\' || index >= escaped.length) {
                append(char)
                continue
            }
            when (val next = escaped[index++]) {
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                'b' -> append('\b')
                'f' -> append('\u000c')
                'u' -> {
                    val hex = escaped.substring(index, (index + 4).coerceAtMost(escaped.length))
                    hex.toIntOrNull(16)?.let { append(it.toChar()) } ?: append("\\u$hex")
                    index = (index + 4).coerceAtMost(escaped.length)
                }
                else -> append(next)
            }
        }
    }
}

private fun conciseResult(value: String): String = value
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .lastOrNull()
    .orEmpty()
    .take(180)

private fun conciseError(value: String): String = conciseResult(value).ifBlank { "执行失败" }
