package backend.lean.workspace

internal data class LeanContext(
    val metadata: String,
    val imports: List<String>,
    val sources: Map<String, String>,
    val sha256: String,
)
