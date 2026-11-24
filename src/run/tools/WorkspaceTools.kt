package run.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.serialization.JSONSerializer
import workspace.TaskWorkspace
import ai.koog.agents.core.tools.annotations.Tool as ToolFunction

/** Adapts shared workspace storage to the runner's Koog file tools. */
class WorkspaceTools(workspace: TaskWorkspace, canCommit: () -> Boolean = { true }) {
    internal val fileSystem = RestrictedWorkspaceFileSystem(workspace, canCommit)
    val toolRegistry = ToolRegistry {
        tool(ListDirectoryTool(fileSystem).named("list_directory"))
        tool(ReadFileTool(fileSystem).named("read_file"))
        tool(WriteFileTool(fileSystem).named("write_file"))
        tool(EditFileTool(fileSystem))
        WorkspaceSearchTools(workspace).asTools().forEach { tool(it) }
        SubmitSolutionTool().asTools().forEach { tool(it) }
    }

    companion object {
        internal const val SUBMIT_SOLUTION = "submit_solution"
    }
}

internal class SubmitSolutionTool : ToolSet {
    @ToolFunction(WorkspaceTools.SUBMIT_SOLUTION)
    @LLMDescription("Submit the current solution files for evaluation. Call after saving your solution.")
    fun submitSolution(): String = "Submission received for evaluation."
}

/** Keep Koog's schema, file operations and model-facing result formatting. */
private fun <Args, Result> Tool<Args, Result>.named(name: String): Tool<Args, Result> {
    val delegate = this
    return object : Tool<Args, Result>(argsType, resultType, descriptor.copy(name = name), metadata) {
        override suspend fun execute(args: Args): Result = delegate.execute(args)

        override fun encodeResultToString(result: Result, serializer: JSONSerializer): String =
            delegate.encodeResultToString(result, serializer)
    }
}
