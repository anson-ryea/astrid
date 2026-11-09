package workspace

/** A reproducible text file snapshot; paths are relative to the sample workspace. */
data class WorkspaceFile(val path: String, val sha256: String, val content: String)
