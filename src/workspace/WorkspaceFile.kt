package workspace

/** A reproducible text file snapshot; paths are relative to the sample workspace. */
data class WorkspaceFile(val path: String, val sha256: String, val content: String)

data class WorkspaceRecord(
    val contextId: String,
    val closureSha256: String,
    val inputs: List<WorkspaceFile>,
    val finalFiles: List<WorkspaceFile>,
)
