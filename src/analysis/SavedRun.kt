package analysis

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import run.RunTaskMetadata
import run.SampleBudget

/** Legacy infrastructure outcomes deliberately retain their historical ambiguity. */
enum class SampleCategory(val value: String) {
    ACCEPTED("accepted"), REJECTED("rejected"), NEEDS_REVIEW("needs_review"),
    EVALUATOR_FAILURE("evaluator_failure"), BUDGET_EXHAUSTED("budget_exhausted"),
    PROVIDER_FAILURE("provider_failure"), EXECUTION_FAILURE("execution_failure"),
    LEGACY_INFRASTRUCTURE_FAILURE("legacy_infrastructure_failure"),
}

data class SavedSample(
    val taskId: String,
    val sampleIndex: Int,
    val schemaVersion: Int,
    val category: SampleCategory,
    val budget: SampleBudget?,
    val firstAttemptAccepted: Boolean,
    val firstAttemptAssessed: Boolean,
    val recordedRepairTurns: Int,
)

data class AnalysisTaskMetadata(val labels: RunTaskMetadata, val source: String)

data class SavedRun(
    val directory: Path,
    val status: String,
    val modelId: String,
    val samplesPerTask: Int,
    val taskIds: List<String>,
    val taskMetadata: Map<String, AnalysisTaskMetadata>,
    val samples: List<SavedSample>,
    val configuration: JsonObject,
    val warnings: List<String>,
)
