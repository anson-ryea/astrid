package console

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.Companion.hyperlink
import com.github.ajalt.mordant.rendering.TextStyles.underline
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.horizontalLayout
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.widgets.ProgressBar
import com.github.ajalt.mordant.widgets.Spinner
import com.github.ajalt.mordant.widgets.Text
import com.github.ajalt.mordant.widgets.Viewport
import kotlin.time.Duration
import java.util.Locale
import run.SamplePhase
import run.RunPhase

/** Builds one frame without changing execution state or writing to the terminal. */
class RunDashboard(private val context: RunDashboardContext) {
    fun render(
        state: RunDashboardState,
        elapsed: Duration,
        width: Int,
        height: Int = 24,
        status: RunDashboardStatus = RunDashboardStatus.Running,
        controlsEnabled: Boolean = false,
    ): Widget {
        val contentWidth = width.coerceAtLeast(1)
        val completed = context.completedTasks(state)
        val timer = formatElapsed(elapsed)
        val spinnerTick = (elapsed.inWholeMilliseconds.coerceAtLeast(0) / 250 % 4).toInt()
        fun line(text: String): Widget = Viewport(Text(text), width = contentWidth, height = 1)
        val title = magenta(bold("astrid")) + "  " + gray(singleLine(context.runId)) +
            if (contentWidth >= 100) gray(" · ${singleLine(context.modelId)}") else ""
        val header = horizontalLayout {
            spacing = 1
            column(0) { this.width = ColumnWidth.Expand() }
            column(1) { this.width = ColumnWidth.Fixed(timer.length) }
            cell(Viewport(Text(title), width = (contentWidth - timer.length - 1).coerceAtLeast(1), height = 1))
            cell(gray(timer))
        }
        val dataset = gray(singleLine(context.datasetName)) + "  " +
            (green + underline + hyperlink(context.datasetPath.toUri().toASCIIString()))(
                singleLine(context.datasetPath.toString()),
            )
        val samples = if (context.samplesPerTask > 1)
            " · ${state.completedSamples}/${context.totalSamples} samples" else ""
        val progressLabel = "$completed/${context.totalTasks} tasks$samples"
        val stats = listOf(
            "✓ ${state.acceptedSamples} success" to green,
            "⚠ ${state.needsReviewSamples} review" to yellow,
            "× ${state.rejectedSamples} failed" to red,
            "! ${state.infrastructureFailureSamples} ${if (state.infrastructureFailureSamples == 1) "error" else "errors"}" to red,
        )
        val outcomes = stats.joinToString("  ") { (text, colour) -> colour(text) }
        val barWidth = minOf((contentWidth / 5).coerceIn(6, 24),
            (contentWidth - progressLabel.length - 2).coerceAtLeast(1))
        val singleSummaryRow = barWidth + 2 + progressLabel.length + 3 +
            stats.sumOf { it.first.length } + 6 <= contentWidth
        val progress = horizontalLayout {
            spacing = 2
            cell(ProgressBar(
                total = context.totalTasks.toLong(), completed = completed.toLong(),
                width = barWidth,
                pendingChar = "─", completeChar = "━", separatorChar = "╸",
                pendingStyle = gray, completeStyle = green, finishedStyle = green,
            ))
            cell(gray(progressLabel))
        }
        val summaryRows = buildList {
            if (singleSummaryRow) {
                add(horizontalLayout {
                    spacing = 3
                    cell(progress)
                    cell(outcomes)
                })
            } else {
                add(progress)
                var row = ""
                var rowWidth = 0
                for ((text, colour) in stats) {
                    if (rowWidth > 0 && rowWidth + 2 + text.length > contentWidth) {
                        add(line(row))
                        row = ""
                        rowWidth = 0
                    }
                    if (rowWidth > 0) { row += "  "; rowWidth += 2 }
                    row += colour(text)
                    rowWidth += text.length
                }
                if (row.isNotEmpty()) add(line(row))
            }
        }
        val active = state.activeSamples.entries.sortedWith(
            compareBy({ it.key.taskId.value }, { it.key.sampleIndex }),
        )
        // Keep the entire frame on screen, including the footer and overflow notice.
        // Leave a spare row so the final newline does not scroll the header into history.
        val availableHeight = (height - 1).coerceAtLeast(1)
        val summary = when {
            availableHeight < 3 -> emptyList()
            availableHeight >= summaryRows.size + 3 -> summaryRows
            else -> listOf(progress)
        }
        val showDataset = availableHeight >= summary.size + 4
        val spacious = availableHeight >= 14
        val fixedRows = (if (availableHeight > 1) 1 else 0) + summary.size + 1 +
            (if (showDataset) 1 else 0) + (if (spacious) 3 else 0)
        val activeBudget = (availableHeight - fixedRows).coerceAtLeast(0)
        val commonPhase = active.map { it.value }.distinct().singleOrNull()
        val runLabel = when (status) {
            RunDashboardStatus.Complete -> "COMPLETE"
            RunDashboardStatus.Failed -> "FAILED"
            RunDashboardStatus.Cancelled -> "CANCELLED"
            RunDashboardStatus.Running -> when {
                commonPhase != null -> phaseLabel(commonPhase)
                active.isNotEmpty() -> "RUNNING"
                state.runPhase == RunPhase.Preparing -> "PREPARING"
                state.runPhase == RunPhase.ResolvingModel -> "CONNECTING"
                else -> "RUNNING"
            }
        }
        val runColour = when (status) {
            RunDashboardStatus.Failed -> red
            RunDashboardStatus.Cancelled -> yellow
            RunDashboardStatus.Complete -> green
            RunDashboardStatus.Running -> when {
                commonPhase != null -> phaseColour(commonPhase)
                active.isNotEmpty() -> cyan
                state.runPhase == RunPhase.Preparing -> gray
                state.runPhase == RunPhase.ResolvingModel -> blue
                else -> cyan
            }
        }
        val body = verticalLayout {
            spacing = 0
            if (availableHeight > 1) cell(header)
            if (spacious) cell("")
            if (showDataset) cell(line("  $dataset"))
            if (spacious) cell("")
            summary.forEach { cell(it) }
            if (spacious) cell("")
            if (activeBudget > 0) {
                val badge = (black on runColour)(bold(" $runLabel "))
                if (status == RunDashboardStatus.Running && active.isNotEmpty()) {
                    cell(Viewport(horizontalLayout {
                        spacing = 2
                        cell(badge)
                        cell(taskList(active, (contentWidth - runLabel.length - 4).coerceAtLeast(0), spinnerTick))
                    }, width = contentWidth, height = 1))
                    if (activeBudget > 1) {
                        val labels = if (contentWidth >= 52)
                            listOf("preparing", "generating", "evaluating", "repairing")
                        else listOf("prep", "gen", "eval", "repair")
                        val legend = SamplePhase.entries.zip(labels).joinToString("  ") { (phase, label) ->
                            phaseColour(phase)("◤") + gray(" $label")
                        }
                        cell(line(legend))
                    }
                } else {
                    val detail = if (status == RunDashboardStatus.Running) when (state.runPhase) {
                        RunPhase.Preparing -> "Validating tasks and preparing workspaces"
                        RunPhase.ResolvingModel -> "Resolving model limits"
                        RunPhase.Running -> if (state.completedSamples.toLong() == context.totalSamples)
                            "Finalising run" else "Waiting for tasks"
                    } else "$completed/${context.totalTasks} tasks · ${state.completedSamples}/${context.totalSamples} samples saved"
                    cell(line(badge + "  " + gray(detail)))
                }
            }
            cell(line(if (controlsEnabled) logControl("logs") else ""))
        }
        return Viewport(body, width = contentWidth)
    }

    private fun phaseLabel(phase: SamplePhase): String = when (phase) {
        SamplePhase.Preparing -> "PREPARING"
        SamplePhase.Generating -> "GENERATING"
        SamplePhase.Evaluating -> "EVALUATING"
        SamplePhase.Repairing -> "REPAIRING"
    }

    private fun phaseColour(phase: SamplePhase): TextColors = when (phase) {
        SamplePhase.Preparing -> gray
        SamplePhase.Generating -> green
        SamplePhase.Evaluating -> blue
        SamplePhase.Repairing -> yellow
    }

    private fun taskList(active: List<Map.Entry<SampleKey, SamplePhase>>, width: Int, tick: Int): Widget {
        fun task(phase: SamplePhase, id: String, suffix: String = ""): Widget = horizontalLayout {
            spacing = 0
            cell(Spinner("◢◣◤◥", style = phaseColour(phase), initial = tick))
            cell(" $id")
            if (suffix.isNotEmpty()) cell(gray(suffix))
        }
        var used = 0
        var shown = 0
        val tokens = mutableListOf<Widget>()
        for ((sample, phase) in active) {
            val suffix = if (context.samplesPerTask > 1) " (sample ${sample.sampleIndex}/${context.samplesPerTask})" else ""
            val text = "◐ ${sample.taskId.value}$suffix"
            val separator = if (shown == 0) 0 else 3
            val remaining = active.size - shown - 1
            val overflowWidth = if (remaining > 0) "   +$remaining more".length else 0
            if (used + separator + text.length + overflowWidth > width) break
            tokens += task(phase, sample.taskId.value, suffix)
            used += separator + text.length
            shown++
        }
        if (shown == 0 && active.isNotEmpty()) {
            val (sample, phase) = active.first()
            val overflow = if (active.size > 1) "   +${active.size - 1} more" else ""
            val labelWidth = (width - overflow.length - 2).coerceAtLeast(0)
            val id = sample.taskId.value.let {
                if (it.length <= labelWidth) it else it.take((labelWidth - 1).coerceAtLeast(0)) + if (labelWidth > 0) "…" else ""
            }
            return horizontalLayout {
                spacing = 0
                cell(task(phase, id))
                if (overflow.isNotEmpty()) cell(gray(overflow))
            }
        }
        return horizontalLayout {
            spacing = 3
            tokens.forEach { cell(it) }
            if (shown < active.size) cell(gray("+${active.size - shown} more"))
        }
    }

    private fun singleLine(text: String): String = text.map { character ->
        if (character.isISOControl() || character == '\u2028' || character == '\u2029') ' ' else character
    }.joinToString("")

    internal fun formatElapsed(elapsed: Duration): String {
        val seconds = elapsed.inWholeSeconds.coerceAtLeast(0)
        return "%02d:%02d:%02d".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
}
