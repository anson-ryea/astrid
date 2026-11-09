package analysis

import run.SampleBudget
import run.SampleResources

data class SavedSample(
    val taskId: String,
    val sampleIndex: Int,
    val schemaVersion: Int,
    val category: SampleCategory,
    val budget: SampleBudget?,
    val firstAttemptAccepted: Boolean,
    val firstAttemptAssessed: Boolean,
    val recordedRepairTurns: Int,
    val resources: SampleResources? = null,
)
