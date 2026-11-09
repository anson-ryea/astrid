package analysis.rendering

import analysis.AnalysisRenderer
import analysis.RunAnalysis
import analysis.SampleCategory
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import text.formatDecimal
import text.formatPercentage
import text.singleLine

internal object TextAnalysisRenderer {
    fun render(analyses: List<RunAnalysis>): String = buildString {
        appendLine("ASTRID saved-run analysis")
        appendLine(AnalysisRenderer.DENOMINATOR_POLICY)
        appendLine("Pass@k: ${AnalysisRenderer.PASS_POLICY}")
        for ((run, summaries) in analyses) {
            val total = summaries.first()
            appendLine()
            appendLine("Run: ${singleLine(run.directory.toString())}")
            appendLine("Model: ${singleLine(run.modelId)} | status: ${run.status}")
            appendLine("Samples: ${total.savedSamples}/${total.expectedSamples} saved; ${total.missingSamples} missing | tasks: ${total.completeTasks}/${total.taskCount} fully saved")
            appendLine(
                "First attempt: ${total.firstAttemptAccepted}/${total.savedSamples} accepted (${
                    formatPercentage(
                        total.firstAttemptRate
                    )
                }); ${total.firstAttemptAssessed} assessed"
            )
            appendLine("Final: ${total.finalAccepted}/${total.savedSamples} accepted (${formatPercentage(total.finalRate)}); ${total.repairedAccepted} accepted after repairs")
            appendLine(
                "Recorded repair turns: ${total.recordedRepairTurns}; ${total.samplesWithRecordedRepairs} samples repaired; mean ${
                    formatDecimal(
                        total.meanRecordedRepairTurns
                    )
                } per saved sample"
            )
            appendLine(
                "Task macro final acceptance: ${formatPercentage(total.taskMacroFinalRate)} | paper macro: ${
                    formatPercentage(
                        total.paperMacroFinalRate
                    )
                }"
            )
            appendLine("Outcomes: " + total.counts.entries.joinToString(", ") { "${it.key.value}=${it.value}" })
            appendLine("Budget limits: " + total.budgets.entries.joinToString(", ") { "${it.key}=${it.value}" })
            for ((k, eligibleTasks, firstAttempt, final) in total.passAtK) appendLine(
                "pass@$k: first=${formatPercentage(firstAttempt)}, final=${
                    formatPercentage(
                        final
                    )
                } ($eligibleTasks/${total.taskCount} eligible tasks)"
            )
            val resources = total.resources.values
            fun resource(name: String) = resources[name]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content ?: "n/a"
            appendLine(
                "Resources: ${resource("samples_with_resources")}/${total.savedSamples} samples measured; requests=${
                    resource(
                        "model_requests"
                    )
                }, tools=${resource("tool_calls")}, elapsed(sum)=${resource("elapsed_ms")} ms"
            )
            appendLine(
                "Tokens: input=${resource("input_tokens")}, output=${resource("output_tokens")}, total=${
                    resource(
                        "total_tokens"
                    )
                }; known total subtotal=${resource("known_total_tokens")}; ${resource("samples_with_complete_total_usage")}/${total.savedSamples} samples have complete total usage"
            )
            appendLine("Cost USD: billed=n/a; estimated=${resource("estimated_cost_usd")} (configured prices, no cache discounts)")
            run.warnings.forEach { appendLine("Note: $it") }
            appendLine("Breakdowns (accepted/saved; missing samples shown separately):")
            summaries.drop(1).forEach { s ->
                val other = s.counts.filter { it.key != SampleCategory.ACCEPTED && it.value > 0 }
                    .entries.joinToString(", ") { "${it.key.value}=${it.value}" }
                appendLine(
                    "  ${s.dimension} ${singleLine(s.label)}: first=${s.firstAttemptAccepted}/${s.savedSamples}, final=${s.finalAccepted}/${s.savedSamples} (${
                        formatPercentage(
                            s.finalRate
                        )
                    }), missing=${s.missingSamples}" +
                            if (other.isEmpty()) "" else "; $other"
                )
            }
        }
    }

}
