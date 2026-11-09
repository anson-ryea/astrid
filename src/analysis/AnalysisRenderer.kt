package analysis

import java.util.Locale
import kotlinx.serialization.json.*

/** Stable machine exports and a concise plain-text report; raw model content is never exported. */
object AnalysisRenderer {
    val denominatorPolicy = "All saved samples are included. NeedsReview and failures are not certified successes; counts remain separate. Missing samples are reported separately."
    val passPolicy = "1-C(n-c,k)/C(n,k), averaged over fully saved tasks with n>=k; first-attempt and final sample successes are separate. No failure exclusions."

    fun json(analyses: List<RunAnalysis>): String = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("schema_version", JsonPrimitive(1))
        put("denominator_policy", JsonPrimitive(denominatorPolicy))
        put("pass_at_k_policy", JsonPrimitive(passPolicy))
        put("runs", JsonArray(analyses.map { analysis -> buildJsonObject {
            val run = analysis.run
            put("run_directory", JsonPrimitive(run.directory.toString()))
            put("model_id", JsonPrimitive(run.modelId))
            put("status", JsonPrimitive(run.status))
            put("configuration", run.configuration)
            put("warnings", JsonArray(run.warnings.map(::JsonPrimitive)))
            put("sample_schema_versions", JsonArray(run.samples.map { it.schemaVersion }.distinct().sorted().map(::JsonPrimitive)))
            put("task_metadata", buildJsonObject {
                run.taskMetadata.toSortedMap().forEach { (id, metadata) -> put(id, buildJsonObject {
                    put("source", JsonPrimitive(metadata.source))
                    put("paper_id", JsonPrimitive(metadata.labels.paperId))
                    put("task_type", JsonPrimitive(metadata.labels.taskType))
                    put("condition", JsonPrimitive(metadata.labels.condition))
                    put("source_result_id", JsonPrimitive(metadata.labels.sourceResultId))
                }) }
            })
            put("summaries", JsonArray(analysis.summaries.map(::summaryJson)))
        } }))
    }) + "\n"

    private fun summaryJson(s: AnalysisSummary): JsonObject = buildJsonObject {
        put("dimension", JsonPrimitive(s.dimension)); put("label", JsonPrimitive(s.label))
        put("tasks", JsonPrimitive(s.taskCount)); put("complete_tasks", JsonPrimitive(s.completeTasks))
        put("expected_samples", JsonPrimitive(s.expectedSamples)); put("saved_samples", JsonPrimitive(s.savedSamples))
        put("missing_samples", JsonPrimitive(s.missingSamples))
        put("outcomes", buildJsonObject { s.counts.forEach { (kind, count) -> put(kind.value, JsonPrimitive(count)) } })
        put("budget_limits", buildJsonObject { s.budgets.forEach { (limit, count) -> put(limit, JsonPrimitive(count)) } })
        put("first_attempt_accepted", JsonPrimitive(s.firstAttemptAccepted))
        put("first_attempt_assessed", JsonPrimitive(s.firstAttemptAssessed))
        put("first_attempt_acceptance_rate", JsonPrimitive(s.firstAttemptRate))
        put("final_accepted", JsonPrimitive(s.finalAccepted)); put("final_acceptance_rate", JsonPrimitive(s.finalRate))
        put("repaired_accepted", JsonPrimitive(s.repairedAccepted))
        put("recorded_repair_turns", JsonPrimitive(s.recordedRepairTurns))
        put("samples_with_recorded_repairs", JsonPrimitive(s.samplesWithRecordedRepairs))
        put("mean_recorded_repair_turns", JsonPrimitive(s.meanRecordedRepairTurns))
        put("task_macro_final_acceptance_rate", JsonPrimitive(s.taskMacroFinalRate))
        put("paper_macro_final_acceptance_rate", JsonPrimitive(s.paperMacroFinalRate))
        put("pass_at_k", JsonArray(s.passAtK.map { estimate -> buildJsonObject {
            put("k", JsonPrimitive(estimate.k)); put("eligible_tasks", JsonPrimitive(estimate.eligibleTasks))
            put("first_attempt", JsonPrimitive(estimate.firstAttempt)); put("final", JsonPrimitive(estimate.final))
        } }))
    }

    fun text(analyses: List<RunAnalysis>): String = buildString {
        appendLine("ASTRID saved-run analysis")
        appendLine(denominatorPolicy)
        appendLine("Pass@k: $passPolicy")
        for (analysis in analyses) {
            val run = analysis.run
            val total = analysis.summaries.first()
            appendLine()
            appendLine("Run: ${safe(run.directory.toString())}")
            appendLine("Model: ${safe(run.modelId)} | status: ${run.status}")
            appendLine("Samples: ${total.savedSamples}/${total.expectedSamples} saved; ${total.missingSamples} missing | tasks: ${total.completeTasks}/${total.taskCount} fully saved")
            appendLine("First attempt: ${total.firstAttemptAccepted}/${total.savedSamples} accepted (${percent(total.firstAttemptRate)}); ${total.firstAttemptAssessed} assessed")
            appendLine("Final: ${total.finalAccepted}/${total.savedSamples} accepted (${percent(total.finalRate)}); ${total.repairedAccepted} accepted after repairs")
            appendLine("Recorded repair turns: ${total.recordedRepairTurns}; ${total.samplesWithRecordedRepairs} samples repaired; mean ${number(total.meanRecordedRepairTurns)} per saved sample")
            appendLine("Task macro final acceptance: ${percent(total.taskMacroFinalRate)} | paper macro: ${percent(total.paperMacroFinalRate)}")
            appendLine("Outcomes: " + total.counts.entries.joinToString(", ") { "${it.key.value}=${it.value}" })
            appendLine("Budget limits: " + total.budgets.entries.joinToString(", ") { "${it.key}=${it.value}" })
            for (estimate in total.passAtK) appendLine("pass@${estimate.k}: first=${percent(estimate.firstAttempt)}, final=${percent(estimate.final)} (${estimate.eligibleTasks}/${total.taskCount} eligible tasks)")
            run.warnings.forEach { appendLine("Note: $it") }
            appendLine("Breakdowns (accepted/saved; missing samples shown separately):")
            analysis.summaries.drop(1).forEach { s ->
                val other = s.counts.filter { it.key != SampleCategory.ACCEPTED && it.value > 0 }
                    .entries.joinToString(", ") { "${it.key.value}=${it.value}" }
                appendLine("  ${s.dimension} ${safe(s.label)}: first=${s.firstAttemptAccepted}/${s.savedSamples}, final=${s.finalAccepted}/${s.savedSamples} (${percent(s.finalRate)}), missing=${s.missingSamples}" +
                    if (other.isEmpty()) "" else "; $other")
            }
        }
    }

    /** One row per run and group; explicit denominators and notes travel with every CSV row. */
    fun csv(analyses: List<RunAnalysis>): String {
        val ks = analyses.flatMap { it.summaries.first().passAtK.map(PassAtK::k) }.distinct().sorted()
        val header = listOf("run_directory", "model_id", "run_status", "dimension", "label", "tasks", "complete_tasks", "expected_samples", "saved_samples", "missing_samples") +
            SampleCategory.entries.map { it.value } + run.SampleBudget.entries.map { "budget_${it.manifestValue}" } +
            listOf("first_attempt_accepted", "first_attempt_assessed", "first_attempt_acceptance_rate", "final_acceptance_rate", "repaired_accepted", "recorded_repair_turns", "samples_with_recorded_repairs", "mean_recorded_repair_turns", "task_macro_final_acceptance_rate", "paper_macro_final_acceptance_rate") +
            ks.flatMap { listOf("first_pass_at_$it", "final_pass_at_$it", "pass_at_${it}_eligible_tasks") } + listOf("denominator_policy", "pass_at_k_policy", "notes")
        return buildString {
            appendLine(header.joinToString(",", transform = ::csvCell))
            analyses.forEach { analysis -> analysis.summaries.forEach { s ->
                val values = listOf(analysis.run.directory, analysis.run.modelId, analysis.run.status, s.dimension, s.label,
                    s.taskCount, s.completeTasks, s.expectedSamples, s.savedSamples, s.missingSamples) +
                    SampleCategory.entries.map { s.counts.getValue(it) } + run.SampleBudget.entries.map { s.budgets.getValue(it.manifestValue) } +
                    listOf(s.firstAttemptAccepted, s.firstAttemptAssessed, s.firstAttemptRate, s.finalRate, s.repairedAccepted,
                        s.recordedRepairTurns, s.samplesWithRecordedRepairs, s.meanRecordedRepairTurns, s.taskMacroFinalRate, s.paperMacroFinalRate) +
                    ks.flatMap { k -> s.passAtK.find { it.k == k }.let { listOf(it?.firstAttempt, it?.final, it?.eligibleTasks) } } +
                    listOf(denominatorPolicy, passPolicy, analysis.run.warnings.joinToString(" "))
                appendLine(values.joinToString(",") { csvCell(it?.toString().orEmpty()) })
            } }
        }
    }

    private fun percent(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f%%", it * 100) } ?: "n/a"
    private fun number(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "n/a"
    private fun safe(value: String): String = value.map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }.joinToString("")
    private fun csvCell(value: String): String = if (value.any { it in ",\"\r\n" }) "\"${value.replace("\"", "\"\"") }\"" else value
}
