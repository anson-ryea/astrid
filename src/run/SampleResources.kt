package run

/** Normalised logical-client usage; null totals mean coverage is incomplete, never zero usage. */
data class SampleResources(
    val elapsedMs: Long,
    val modelMs: Long,
    val toolMs: Long,
    val evaluationMs: Long,
    val modelRequests: Int,
    val modelResponses: Int,
    val toolCalls: Int,
    val workspaceToolCalls: Int,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
    val knownInputTokens: Long,
    val knownOutputTokens: Long,
    val knownTotalTokens: Long,
    val responsesWithInputUsage: Int,
    val responsesWithOutputUsage: Int,
    val responsesWithTotalUsage: Int,
    val effectiveSeed: Int?,
    val estimatedCostUsd: Double?,
    val requestOutputTokenCaps: List<Int?>,
)
