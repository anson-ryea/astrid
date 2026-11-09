package analysis

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

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
