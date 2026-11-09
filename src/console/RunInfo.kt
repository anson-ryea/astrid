package console

import com.github.ajalt.mordant.rendering.*
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.Companion.hyperlink
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.underline
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.HorizontalRule
import com.github.ajalt.mordant.widgets.Text
import com.github.ajalt.mordant.widgets.Viewport
import text.singleLine
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Run metadata only: never reads task artefacts or private evaluation material. */
internal class RunInfo(private val context: RunDashboardContext) {
    private val offset = AtomicInteger()

    fun scrollBy(rows: Int) {
        offset.updateAndGet { (it.toLong() + rows).coerceIn(0, Int.MAX_VALUE.toLong()).toInt() }
    }

    fun home() {
        offset.set(0)
    }

    fun end() {
        offset.set(Int.MAX_VALUE)
    }

    fun render(
        width: Int, height: Int, controlsEnabled: Boolean,
        status: RunDashboardStatus = RunDashboardStatus.Running
    ): Widget {
        val columns = width.coerceAtLeast(1)
        val rows = (height - 1).coerceAtLeast(1)
        val headerRows = minOf(2, (rows - 1).coerceAtLeast(0))
        val bodyRows = (rows - headerRows - 1).coerceAtLeast(0)
        val body = object : Widget {
            override fun measure(t: Terminal, width: Int) = WidthRange(width, width)
            override fun render(t: Terminal, width: Int): Lines {
                val lines = details(width, status).render(t, width).lines
                val start = offset.updateAndGet { it.coerceAtMost((lines.size - bodyRows).coerceAtLeast(0)) }
                return Lines(lines.drop(start).take(bodyRows))
            }
        }
        return Viewport(verticalLayout {
            spacing = 0
            if (headerRows > 0) cell(Viewport(Text(bold("info")), width = columns, height = 1))
            if (headerRows > 1) cell(HorizontalRule(ruleStyle = gray))
            if (bodyRows > 0) cell(Viewport(body, width = columns, height = bodyRows))
            cell(
                Viewport(
                    Text(
                        when {
                            status == RunDashboardStatus.Quitting -> yellow("Quitting… waiting for cleanup")
                            controlsEnabled -> runControls(RunConsolePage.Info)
                            else -> ""
                        }
                    ), width = columns, height = 1
                )
            )
        }, width = columns, height = rows)
    }

    private fun details(width: Int, status: RunDashboardStatus): Widget = verticalLayout {
        spacing = 0
        fun text(value: String) = Text(value, whitespace = Whitespace.PRE_WRAP, overflowWrap = OverflowWrap.BREAK_WORD)
        fun section(title: String, gap: Boolean = true) {
            if (gap) cell("")
            cell(text((bold + underline)(title)))
        }

        fun value(label: String, value: String) {
            val key = gray(if (width >= 60) label.padEnd(22) else label)
            cell(text("  $key  $value"))
        }

        fun path(label: String, path: Path?) {
            value(
                label, if (path == null) gray("not supplied") else {
                    val absolute = path.toAbsolutePath().normalize()
                    (green + underline + hyperlink(absolute.toUri().toASCIIString()))(singleLine(absolute.toString()))
                }
            )
        }

        fun setting(label: String, setting: Any?, absent: String = "unlimited") =
            value(label, green(singleLine(setting?.toString() ?: absent)))

        section("run", gap = false)
        value("id", magenta(singleLine(context.runId)))
        value(
            "status", when (status) {
                RunDashboardStatus.Quitting -> yellow("QUITTING")
                RunDashboardStatus.Running -> cyan("RUNNING")
                RunDashboardStatus.Complete -> green("COMPLETE")
                RunDashboardStatus.Failed -> red("FAILED")
                RunDashboardStatus.Cancelled -> yellow("CANCELLED")
            }
        )
        setting("model", context.modelId)
        context.backendId?.let { setting("backend", it) }
        setting("tasks / samples", "${context.totalTasks} / ${context.totalSamples}")
        section("paths")
        path("run config", context.configPath)
        path("dataset", context.datasetPath)
        path("output", context.outputPath)
        section("configuration")
        setting("samples per task", context.samplesPerTask)
        context.runConfig?.let { config ->
            setting("concurrent tasks", config.maxConcurrentTasks)
            setting("temperature", config.generation.temperature, "provider default")
            setting("top p", config.generation.topP, "provider default")
            setting("top k", config.generation.topK, "provider default")
            setting("base seed", config.generation.seed, "provider default")
            setting("sample deadline", config.limits.timeout)
            setting("sample total tokens", config.limits.maxTotalTokens)
            setting("input USD / 1M", config.pricing?.inputUsdPerMillion, "estimate unavailable")
            setting("output USD / 1M", config.pricing?.outputUsdPerMillion, "estimate unavailable")
            setting("context length", config.contextLength, "provider default")
            setting("output tokens", config.maxOutputTokens, "provider default")
            setting("supports tools", config.supportsTools)
            setting("repair turns", config.maxRepairTurns)
            setting("agent iterations", config.maxAgentIterations)
            setting("tool calls", config.maxToolCallsPerAttempt)
            setting("workspace tool calls", config.maxWorkspaceToolCallsPerAttempt)
        }
        section("tasks")
        val byPaper = context.taskSubmissions.keys.groupBy { id ->
            context.taskProblems[id]?.paper ?: "paper_${id.value.substringAfter("task_").substringBefore('.')}"
        }.toSortedMap(compareBy<String> { it.substringAfter("paper_").toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
        byPaper.entries.forEachIndexed { paperIndex, (paper, tasks) ->
            val lastPaper = paperIndex == byPaper.size - 1
            cell(text(gray(if (lastPaper) "└─ " else "├─ ") + green(singleLine(paper))))
            val paperPrefix = if (lastPaper) "   " else "│  "
            val problems = tasks.groupBy { id ->
                context.taskProblems[id]?.problem ?: id.value.substringAfter('_').substringAfter('_')
            }.toSortedMap()
            problems.entries.forEachIndexed { problemIndex, (problem, ids) ->
                val lastProblem = problemIndex == problems.size - 1
                cell(text(gray(paperPrefix + if (lastProblem) "└─ " else "├─ ") + cyan(singleLine(problem))))
                val prefix = paperPrefix + if (lastProblem) "   " else "│  "
                val ordered = ids.sortedBy { it.value.substringAfter('.').substringBefore('_').toInt() }
                ordered.forEachIndexed { index, id ->
                    cell(
                        text(
                            gray(prefix + if (index == ordered.size - 1) "└─ " else "├─ ") + id.value +
                                    gray("  ${singleLine(context.taskSubmissions.getValue(id).value)}")
                        )
                    )
                }
            }
        }
    }
}
