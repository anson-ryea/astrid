package workspace

data class WorkspaceRecord(
    val contextId: String,
    val closureSha256: String,
    val inputs: List<WorkspaceFile>,
    val finalFiles: List<WorkspaceFile>,
)
