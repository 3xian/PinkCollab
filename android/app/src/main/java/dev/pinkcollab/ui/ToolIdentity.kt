package dev.pinkcollab.ui

internal enum class ToolFamily { Read, Search, Edit, Write, Command, Code, Delegate, Wait, Other }

/** Normalized identity, independent of the labels and summaries shown by each surface. */
@JvmInline
internal value class ToolIdentity(val name: String) {
    val family: ToolFamily
        get() = when (name) {
            "read" -> ToolFamily.Read
            "grep", "find", "glob", "search", "web_search" -> ToolFamily.Search
            "edit", "ast_edit", "apply_patch" -> ToolFamily.Edit
            "write" -> ToolFamily.Write
            "bash", "shell", "exec" -> ToolFamily.Command
            "eval", "python", "js" -> ToolFamily.Code
            "task", "agent" -> ToolFamily.Delegate
            "wait" -> ToolFamily.Wait
            else -> ToolFamily.Other
        }

    // The live strip recognizes fewer prefixes, but more exact command aliases, than the timeline.
    val workFamily: ToolFamily
        get() = when (name) {
            "exec_command", "command", "execute", "run_command" -> ToolFamily.Command
            else -> when {
                name.startsWith("read_") -> ToolFamily.Read
                name.startsWith("search_") || name.startsWith("grep_") -> ToolFamily.Search
                name.startsWith("edit_") -> ToolFamily.Edit
                name.startsWith("write_") -> ToolFamily.Write
                else -> family
            }
        }

    val activityStage: ActivityStage
        get() = when (family) {
            ToolFamily.Read, ToolFamily.Search -> ActivityStage.Explore
            ToolFamily.Edit, ToolFamily.Write -> ActivityStage.Change
            ToolFamily.Command, ToolFamily.Code -> ActivityStage.Execute
            else -> when {
                name == "lsp" || name.startsWith("read_") || name.startsWith("grep_") ||
                    name.startsWith("find_") || name.startsWith("glob_") || name.startsWith("search_") ||
                    name.startsWith("lsp_") -> ActivityStage.Explore
                name.startsWith("edit_") || name.startsWith("write_") || name.startsWith("ast_edit_") -> ActivityStage.Change
                name == "test" || name.startsWith("bash_") || name.startsWith("shell_") ||
                    name.startsWith("exec_") || name.startsWith("test_") || name.startsWith("python_") ||
                    name.startsWith("eval_") -> ActivityStage.Execute
                else -> ActivityStage.Other
            }
        }
}

internal fun toolIdentity(name: String): ToolIdentity =
    ToolIdentity(name.substringAfterLast('.').substringAfterLast('/').lowercase())
