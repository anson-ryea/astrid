package console

import analysis.AnalysisRenderer
import analysis.AnalysisSummary
import analysis.RunAnalysis
import analysis.SampleCategory
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.Widget
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import text.formatDecimal
import text.formatPercentage
import text.singleLine
import java.util.*

/** A complete report printed once into normal terminal scrollback. */
internal object AnalysisReport {
    fun render(analyses: List<RunAnalysis>, width: Int): Widget = InfoLayout(width).apply {
        line(magenta(bold("astrid")) + "  " + bold("saved-run analysis"))
        rule()
        analyses.forEachIndexed { index, analysis ->
            if (index > 0) {
                line("")
                rule()
            }
            reportRun(analysis)
        }
        section("interpretation")
        value("denominators", gray(AnalysisRenderer.DENOMINATOR_POLICY))
        value("pass@k", gray(AnalysisRenderer.PASS_POLICY))
    }.render()

    private fun InfoLayout.reportRun(analysis: RunAnalysis) {
        val (run, summaries) = analysis
        val total = summaries.first()
        section("run", gap = false)
        value("id", magenta(singleLine(run.directory.fileName?.toString() ?: run.directory.toString())))
        value("model", green(singleLine(run.modelId)))
        val status = when (run.status) {
            "complete" -> green
            "failed" -> red
            else -> yellow
        }
        value("status", status(singleLine(run.status.uppercase(Locale.ROOT))))
        path("directory", run.directory)

        section("coverage")
        setting("samples saved", "${total.savedSamples} / ${total.expectedSamples}")
        value("missing samples", count(total.missingSamples, yellow))
        setting("complete tasks", "${total.completeTasks} / ${total.taskCount}")

        section("acceptance")
        value("first attempt", accepted(total.firstAttemptAccepted, total.savedSamples, total.firstAttemptRate))
        setting("first assessed", total.firstAttemptAssessed)
        value("final", accepted(total.finalAccepted, total.savedSamples, total.finalRate))
        setting("accepted after repairs", total.repairedAccepted)
        setting("task macro final", formatPercentage(total.taskMacroFinalRate))
        setting("paper macro final", formatPercentage(total.paperMacroFinalRate))

        section("repairs")
        setting("recorded turns", total.recordedRepairTurns)
        setting("samples repaired", total.samplesWithRecordedRepairs)
        setting("mean turns / sample", formatDecimal(total.meanRecordedRepairTurns))

        section("outcomes")
        total.counts.forEach { (category, amount) ->
            val colour = when (category) {
                SampleCategory.ACCEPTED -> green
                SampleCategory.NEEDS_REVIEW, SampleCategory.BUDGET_EXHAUSTED,
                SampleCategory.LEGACY_INFRASTRUCTURE_FAILURE -> yellow

                else -> red
            }
            value(category.value.replace('_', ' '), count(amount.toLong(), colour))
        }

        section("budget limits")
        total.budgets.forEach { (limit, amount) ->
            value(limit.replace('_', ' '), count(amount.toLong(), yellow))
        }

        section("pass@k")
        total.passAtK.forEach { estimate ->
            value(
                "pass@${estimate.k}",
                "first=${percentage(estimate.firstAttempt)}, final=${percentage(estimate.final)} " +
                        gray("(${estimate.eligibleTasks}/${total.taskCount} eligible tasks)")
            )
        }

        reportResources(total)

        for ((dimension, title) in listOf(
            "paper" to "papers",
            "task_type" to "task types",
            "condition" to "conditions",
            "task" to "tasks"
        )) {
            section(title)
            val groups = summaries.filter { it.dimension == dimension }
            groups.forEachIndexed { index, summary ->
                val last = index == groups.lastIndex
                val branch = if (last) "└─ " else "├─ "
                val continuation = if (last) "   " else "│  "
                line(gray("  $branch") + cyan(singleLine(summary.label)))
                line(
                    gray("  $continuation") +
                            "first=${green("${summary.firstAttemptAccepted}/${summary.savedSamples}")}, " +
                            "final=${green("${summary.finalAccepted}/${summary.savedSamples}")} (${percentage(summary.finalRate)}), " +
                            "missing=${count(summary.missingSamples, yellow)}"
                )
                val other = summary.counts.filter { it.key != SampleCategory.ACCEPTED && it.value > 0 }
                if (other.isNotEmpty()) line(gray("  $continuation") + other.entries.joinToString(", ") {
                    "${it.key.value.replace('_', ' ')}=${it.value}"
                })
            }
        }

        if (run.warnings.isNotEmpty()) {
            section("notes")
            run.warnings.forEach { line(yellow("  • ${singleLine(it)}")) }
        }
    }

    private fun InfoLayout.reportResources(total: AnalysisSummary) {
        val resources = total.resources.values
        fun resource(name: String): String =
            resources[name]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content ?: "n/a"

        section("resources")
        setting("samples measured", "${resource("samples_with_resources")} / ${total.savedSamples}")
        setting("model requests", resource("model_requests"))
        setting("model responses", resource("model_responses"))
        setting("tool calls", resource("tool_calls"))
        setting("workspace tool calls", resource("workspace_tool_calls"))
        for ((label, key) in listOf(
            "elapsed (sum)" to "elapsed_ms",
            "model time" to "model_ms",
            "tool time" to "tool_ms",
            "evaluation time" to "evaluation_ms"
        )) {
            setting(label, resource(key).let { if (it == "n/a") it else "$it ms" })
        }

        section("tokens")
        for (kind in listOf("input", "output", "total")) {
            setting(kind, resource("${kind}_tokens"))
            setting("known $kind subtotal", resource("known_${kind}_tokens"))
            setting(
                "complete $kind usage",
                "${resource("samples_with_complete_${kind}_usage")} / ${total.savedSamples} samples"
            )
        }

        section("cost (USD)")
        value("billed", gray("n/a"))
        setting("estimated", resource("estimated_cost_usd"))
        line(gray("  ${resource("cost_policy")}"))
    }

    private fun accepted(count: Int, samples: Int, rate: Double?): String =
        green("$count/$samples accepted (${formatPercentage(rate)})")

    private fun count(value: Long, colour: com.github.ajalt.mordant.rendering.TextColors): String =
        (if (value == 0L) gray else colour)(value.toString())

    private fun percentage(value: Double?): String = if (value == null) gray("n/a") else green(formatPercentage(value))
}
