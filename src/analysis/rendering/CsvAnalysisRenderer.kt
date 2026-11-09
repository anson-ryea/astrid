package analysis.rendering

import analysis.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

internal object CsvAnalysisRenderer {
    /** One row per run and group; explicit denominators and notes travel with every CSV row. */
    fun render(analyses: List<RunAnalysis>): String {
        val ks = analyses.flatMap { it.summaries.first().passAtK.map(PassAtK::k) }.distinct().sorted()
        val header = buildList {
            addAll(
                listOf(
                    "run_directory",
                    "model_id",
                    "run_status",
                    "dimension",
                    "label",
                    "tasks",
                    "complete_tasks",
                    "expected_samples",
                    "saved_samples",
                    "missing_samples"
                )
            )
            SampleCategory.entries.mapTo(this) { it.value }
            run.SampleBudget.entries.mapTo(this) { "budget_${it.manifestValue}" }
            addAll(
                listOf(
                    "first_attempt_accepted",
                    "first_attempt_assessed",
                    "first_attempt_acceptance_rate",
                    "final_acceptance_rate",
                    "repaired_accepted",
                    "recorded_repair_turns",
                    "samples_with_recorded_repairs",
                    "mean_recorded_repair_turns",
                    "task_macro_final_acceptance_rate",
                    "paper_macro_final_acceptance_rate"
                )
            )
            ks.flatMapTo(this) {
                listOf(
                    "first_pass_at_$it",
                    "final_pass_at_$it",
                    "pass_at_${it}_eligible_tasks"
                )
            }
            ResourceSummary.columns.mapTo(this) { "resources_$it" }
            addAll(
                listOf(
                    "denominator_policy",
                    "pass_at_k_policy",
                    "notes"
                )
            )
        }
        return buildString {
            appendLine(header.joinToString(",", transform = ::csvCell))
            analyses.forEach { analysis ->
                analysis.summaries.forEach { s ->
                    val values = buildList {
                        addAll(
                            listOf(
                                analysis.run.directory, analysis.run.modelId, analysis.run.status, s.dimension, s.label,
                                s.taskCount, s.completeTasks, s.expectedSamples, s.savedSamples, s.missingSamples
                            )
                        )
                        SampleCategory.entries.mapTo(this) { s.counts.getValue(it) }
                        run.SampleBudget.entries.mapTo(this) {
                            s.budgets.getValue(
                                it.manifestValue
                            )
                        }
                        addAll(
                            listOf(
                                s.firstAttemptAccepted,
                                s.firstAttemptAssessed,
                                s.firstAttemptRate,
                                s.finalRate,
                                s.repairedAccepted,
                                s.recordedRepairTurns,
                                s.samplesWithRecordedRepairs,
                                s.meanRecordedRepairTurns,
                                s.taskMacroFinalRate,
                                s.paperMacroFinalRate
                            )
                        )
                        ks.flatMapTo(this) { k ->
                            s.passAtK.find { it.k == k }
                                .let { listOf(it?.firstAttempt, it?.final, it?.eligibleTasks) }
                        }
                        ResourceSummary.columns.mapTo(this) { s.resources.values[it]?.takeUnless { value -> value == JsonNull }?.jsonPrimitive?.content }
                        addAll(
                            listOf(
                                AnalysisRenderer.DENOMINATOR_POLICY,
                                AnalysisRenderer.PASS_POLICY,
                                analysis.run.warnings.joinToString(" ")
                            )
                        )
                    }
                    appendLine(values.joinToString(",") { csvCell(it?.toString().orEmpty()) })
                }
            }
        }
    }

    private fun csvCell(value: String): String =
        if (value.any { it in ",\"\r\n" }) "\"${value.replace("\"", "\"\"")}\"" else value
}
