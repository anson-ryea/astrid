package workspace

/** Backend-supplied editable files and scratch naming rules for one sample. */
data class WorkspaceSpecification(
    val initialFiles: Map<String, String>,
    val scratchPath: Regex,
    val maxFileBytes: Int = 256 * 1024,
    val maxWritableFiles: Int = 32,
) {
    init {
        require(initialFiles.isNotEmpty()) { "workspace must have an editable submission file" }
        require(maxFileBytes > 0) { "file size limit must be positive" }
        require(maxWritableFiles >= initialFiles.size) { "writable file limit is too small" }
    }
}
