package analysis

data class AnalysisSummary(
    val dimension: String,
    val label: String,
    val taskCount: Int,
    val completeTasks: Int,
    val expectedSamples: Long,
    val savedSamples: Int,
    val counts: Map<SampleCategory, Int>,
    val budgets: Map<String, Int>,
    val firstAttemptAccepted: Int,
    val firstAttemptAssessed: Int,
    val repairedAccepted: Int,
    val recordedRepairTurns: Long,
    val samplesWithRecordedRepairs: Int,
    val taskMacroFinalRate: Double?,
    val paperMacroFinalRate: Double?,
    val passAtK: List<PassAtK>,
    val resources: ResourceSummary,
) {
    val missingSamples: Long get() = expectedSamples - savedSamples
    val finalAccepted: Int get() = counts.getValue(SampleCategory.ACCEPTED)
    val firstAttemptRate: Double? get() = rate(firstAttemptAccepted, savedSamples)
    val finalRate: Double? get() = rate(finalAccepted, savedSamples)
    val meanRecordedRepairTurns: Double? get() = if (savedSamples == 0) null else recordedRepairTurns.toDouble() / savedSamples
}

private fun rate(count: Int, total: Int): Double? = if (total == 0) null else count.toDouble() / total
