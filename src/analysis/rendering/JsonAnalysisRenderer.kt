package analysis.rendering

import analysis.AnalysisRenderer
import analysis.AnalysisSummary
import analysis.RunAnalysis
import kotlinx.serialization.json.*

internal object JsonAnalysisRenderer {
    fun render(analyses: List<RunAnalysis>): String =
        Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("schema_version", JsonPrimitive(1))
            put("denominator_policy", JsonPrimitive(AnalysisRenderer.DENOMINATOR_POLICY))
            put("pass_at_k_policy", JsonPrimitive(AnalysisRenderer.PASS_POLICY))
            put("runs", JsonArray(analyses.map { analysis ->
                buildJsonObject {
                    val run = analysis.run
                    put("run_directory", JsonPrimitive(run.directory.toString()))
                    put("model_id", JsonPrimitive(run.modelId))
                    put("status", JsonPrimitive(run.status))
                    put("configuration", run.configuration)
                    put("warnings", JsonArray(run.warnings.map(::JsonPrimitive)))
                    put(
                        "sample_schema_versions",
                        JsonArray(run.samples.map { it.schemaVersion }.distinct().sorted().map(::JsonPrimitive))
                    )
                    put("task_metadata", buildJsonObject {
                        run.taskMetadata.toSortedMap().forEach { (id, metadata) ->
                            put(id, buildJsonObject {
                                put("source", JsonPrimitive(metadata.source))
                                put("paper_id", JsonPrimitive(metadata.labels.paperId))
                                put("task_type", JsonPrimitive(metadata.labels.taskType))
                                put("condition", JsonPrimitive(metadata.labels.condition))
                                put("source_result_id", JsonPrimitive(metadata.labels.sourceResultId))
                            })
                        }
                    })
                    put("summaries", JsonArray(analysis.summaries.map(::summaryJson)))
                }
            }))
        }) + "\n"

    private fun summaryJson(s: AnalysisSummary): JsonObject = buildJsonObject {
        put("dimension", JsonPrimitive(s.dimension))
        put("label", JsonPrimitive(s.label))
        put("tasks", JsonPrimitive(s.taskCount))
        put("complete_tasks", JsonPrimitive(s.completeTasks))
        put("expected_samples", JsonPrimitive(s.expectedSamples))
        put("saved_samples", JsonPrimitive(s.savedSamples))
        put("missing_samples", JsonPrimitive(s.missingSamples))
        put("outcomes", buildJsonObject { s.counts.forEach { (kind, count) -> put(kind.value, JsonPrimitive(count)) } })
        put(
            "budget_limits",
            buildJsonObject { s.budgets.forEach { (limit, count) -> put(limit, JsonPrimitive(count)) } })
        put("first_attempt_accepted", JsonPrimitive(s.firstAttemptAccepted))
        put("first_attempt_assessed", JsonPrimitive(s.firstAttemptAssessed))
        put("first_attempt_acceptance_rate", JsonPrimitive(s.firstAttemptRate))
        put("final_accepted", JsonPrimitive(s.finalAccepted))
        put("final_acceptance_rate", JsonPrimitive(s.finalRate))
        put("repaired_accepted", JsonPrimitive(s.repairedAccepted))
        put("recorded_repair_turns", JsonPrimitive(s.recordedRepairTurns))
        put("samples_with_recorded_repairs", JsonPrimitive(s.samplesWithRecordedRepairs))
        put("mean_recorded_repair_turns", JsonPrimitive(s.meanRecordedRepairTurns))
        put("task_macro_final_acceptance_rate", JsonPrimitive(s.taskMacroFinalRate))
        put("paper_macro_final_acceptance_rate", JsonPrimitive(s.paperMacroFinalRate))
        put("resources", s.resources.values)
        put("pass_at_k", JsonArray(s.passAtK.map { estimate ->
            buildJsonObject {
                put("k", JsonPrimitive(estimate.k))
                put("eligible_tasks", JsonPrimitive(estimate.eligibleTasks))
                put("first_attempt", JsonPrimitive(estimate.firstAttempt))
                put("final", JsonPrimitive(estimate.final))
            }
        }))
    }

}
