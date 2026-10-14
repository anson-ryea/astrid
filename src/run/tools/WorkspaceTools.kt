package run.tools

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import workspace.TaskWorkspace

/** Adapts shared workspace storage to the runner's Koog file tools. */
class WorkspaceTools(workspace: TaskWorkspace) {
    internal val fileSystem = RestrictedWorkspaceFileSystem(workspace)
    val toolRegistry = ToolRegistry {
        tool(ListDirectoryTool(fileSystem))
        tool(ReadFileTool(fileSystem))
        tool(WriteFileTool(fileSystem))
        tool(EditFileTool(fileSystem))
        WorkspaceSearchTools(workspace).asTools().forEach { tool(it) }
    }
}
