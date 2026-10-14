package run.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import workspace.TaskWorkspace

/** Content search complements Koog's built-in file operations using the same allowlist. */
class WorkspaceSearchTools(private val workspace: TaskWorkspace) : ToolSet {
    @Tool("search_files")
    @LLMDescription("Search visible workspace files for a literal string; returns at most 100 matching lines with /workspace paths.")
    fun searchFiles(text: String): String {
        require(text.isNotEmpty()) { "search text must not be empty" }
        return workspace.visibleFiles().asSequence().flatMap { path ->
            workspace.read(path).lineSequence().mapIndexedNotNull { index, line ->
                if (line.contains(text)) "/workspace/$path:${index + 1}: $line" else null
            }
        }.take(100).joinToString("\n")
    }
}
