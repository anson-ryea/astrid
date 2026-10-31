package console

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import domain.EvaluationResult
import domain.SubmissionKind
import domain.TaskId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import run.RunEvent
import run.SamplePhase

class RunDashboardTest {
    private val proof = TaskId("task_1.1_theorem_11")
    private val statement = TaskId("task_1.2_theorem_12")
    private val context = RunDashboardContext(
        "proofs-01", "test-model", Path.of("/datasets/pl-suite"),
        mapOf(proof to SubmissionKind("lean_proof_body"), statement to SubmissionKind("lean_statement")), 2,
        outputPath = Path.of("/runs/proofs-01"),
    )

    private fun terminal(width: Int, colour: Boolean = false) = Terminal(
        width = width,
        terminalInterface = TerminalRecorder(
            width = width, ansiLevel = if (colour) AnsiLevel.TRUECOLOR else AnsiLevel.NONE,
        ),
    )

    @Test
    fun alignsElapsedTimeAtTheRightAndReservesAnEmptyFooter() {
        val output = terminal(160).render(RunDashboard(context).render(
            RunDashboardState(), 1.hours + 2.minutes + 3.seconds, 160,
        ))
        val lines = output.lines()
        assertTrue(lines.first().startsWith("astrid  proofs-01", ignoreCase = true))
        assertTrue(lines.first().trimEnd().endsWith("01:02:03"))
        assertEquals(160, lines.first().trimEnd().length)
        assertTrue(lines.last().isBlank())
        assertContains(lines[2], "pl-suite")
        assertContains(lines[2], "/datasets/pl-suite")
        assertContains(lines[3], "output")
        assertContains(lines[3], "/runs/proofs-01")
        assertEquals(lines[2].indexOf("/datasets"), lines[3].indexOf("/runs"))
        assertContains(output, "PREPARING")
        assertFalse(output.contains("quit"))
    }

    @Test
    fun showsConcurrentStagesAndDoesNotConfuseSamplesWithCompletedTasks() {
        var state = RunDashboardState()
            .reduce(RunEvent.SampleFinished(proof, 1, EvaluationResult.Accepted))
            .reduce(RunEvent.PhaseChanged(proof, 2, SamplePhase.Generating))
            .reduce(RunEvent.PhaseChanged(statement, 1, SamplePhase.Evaluating))
        val dashboard = RunDashboard(context)
        val output = terminal(160).render(dashboard.render(state, 10.seconds, 160))
        assertContains(output, "0/2 tasks")
        assertContains(output, "1/4 samples")
        assertContains(output, "1 success")
        assertContains(output, "RUNNING")
        assertContains(output, "generating")
        assertContains(output, "evaluating")
        assertContains(output, proof.value)
        assertContains(output, statement.value)
        assertContains(output, "sample 2/2")
        val taskRow = output.lines().indexOfFirst { it.contains(proof.value) }
        assertContains(output.lines()[taskRow], statement.value)
        assertContains(output.lines()[taskRow + 1], "preparing")
        assertContains(output.lines()[taskRow + 1], "repairing")
        assertTrue(output.indexOf("success") < output.indexOf("review"))
        assertTrue(output.indexOf("review") < output.indexOf("failed"))

        state = state.reduce(RunEvent.SampleFinished(proof, 2, EvaluationResult.Rejected("invalid proof")))
        val updated = terminal(160).render(dashboard.render(state, 12.seconds, 160))
        assertContains(updated, "1/2 tasks")
        assertContains(updated, "2/4 samples")
        assertContains(updated, "1 failed")
        assertEquals(1, context.completedTasks(state))
    }

    @Test
    fun keepsOutcomesDistinctAndFitsNarrowTerminals() {
        val state = RunDashboardState()
            .reduce(RunEvent.SampleFinished(proof, 1, EvaluationResult.InfrastructureFailure("provider unavailable")))
            .reduce(RunEvent.SampleFinished(statement, 1, EvaluationResult.NeedsReview("manual check")))
        for (width in listOf(32, 60, 80, 120, 160)) {
            val output = terminal(width).render(RunDashboard(context).render(state, 27.hours, width))
            assertTrue(output.lines().all { it.length <= width }, "overflow at $width columns")
            assertContains(output, "0 success")
            assertContains(output, "0 failed")
            assertContains(output, "1 error")
            assertContains(output, "1 review")
            assertContains(output.lines().first(), "27:00:00")
            assertTrue(output.lines().last().isBlank())
        }
    }

    @Test
    fun fitsShortScreensIncludingTheOverflowNoticeAndReservedFooter() {
        val ids = (1..4).map { TaskId("task_1.${it}_theorem_11") }
        val metadata = context.copy(taskSubmissions = ids.associateWith { SubmissionKind("lean_proof_body") })
        val state = ids.fold(RunDashboardState()) { snapshot, id ->
            snapshot.reduce(RunEvent.PhaseChanged(id, 1, SamplePhase.Generating))
        }
        for (width in listOf(32, 80, 160)) {
            for (height in listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 24)) {
                val frame = RunDashboard(metadata).render(state, 5.seconds, width, height)
                val rendered = frame.render(terminal(width), width)
                assertTrue(rendered.height <= height - 1,
                    "${rendered.height} rows overflow a $width x $height terminal")
                val text = terminal(width).render(frame)
                assertTrue(text.lines().last().isBlank())
                if (height >= 8) {
                    assertContains(text, "astrid", ignoreCase = true)
                    assertContains(text, "GENERATING")
                }
                if (width == 160 && height >= 8) {
                    val expectedSpacers = when (height) { 8 -> 0; 9 -> 1; 10 -> 2; else -> 3 }
                    assertEquals(expectedSpacers, text.lines().dropLast(1).count { it.isBlank() },
                        "spacing compacted before it needed to at $height rows")
                    assertContains(text, "/datasets/pl-suite")
                    assertContains(text, "/runs/proofs-01")
                    assertContains(text, "repairing")
                }
            }
        }
    }

    @Test
    fun rendersFileHyperlinksAndColouredBadgesWithoutLeakingControlCharactersFromMetadata() {
        val untrusted = context.copy(runId = "proofs\u001b[2J\ninjected")
        val state = RunDashboardState().reduce(RunEvent.PhaseChanged(proof, 1, SamplePhase.Repairing))
        val output = terminal(160, colour = true).render(RunDashboard(untrusted).render(state, 5.seconds, 160))
        assertContains(output, "file:///datasets/pl-suite")
        assertContains(output, "file:///runs/proofs-01")
        assertContains(output, "REPAIRING")
        assertFalse(output.contains("\u001b[2J"))
        assertContains(output, "\u001b[")
    }

    @Test
    fun writesReferenceFramesForVisualReview() {
        val ids = (1..12).map { TaskId("task_1.${it}_theorem_11") }
        val metadata = context.copy(
            datasetRoot = Path.of("/Users/anson/Documents/Projects/astrid/dataset"),
            taskSubmissions = ids.associateWith { SubmissionKind("lean_proof_body") }, samplesPerTask = 1,
        )
        var state = RunDashboardState()
        ids.take(7).forEachIndexed { index, id ->
            state = state.reduce(RunEvent.SampleFinished(id, 1,
                if (index < 6) EvaluationResult.Accepted else EvaluationResult.Rejected("invalid proof")))
        }
        state = state.reduce(RunEvent.PhaseChanged(ids[7], 1, SamplePhase.Generating))
            .reduce(RunEvent.PhaseChanged(ids[8], 1, SamplePhase.Evaluating))
            .reduce(RunEvent.PhaseChanged(ids[9], 1, SamplePhase.Repairing))
        val directory = Path.of("build/reports/console")
        Files.createDirectories(directory)
        for (width in listOf(80, 160)) {
            val frame = RunDashboard(metadata).render(state, 13.minutes + 42.seconds, width)
            Files.writeString(directory.resolve("dashboard-$width.ansi"), terminal(width, colour = true).render(frame))
            Files.writeString(directory.resolve("dashboard-$width.txt"), terminal(width).render(frame))
        }
        val output = Files.readString(directory.resolve("dashboard-160.txt"))
        assertContains(output, "7/12 tasks")
        assertContains(output, "RUNNING")
        assertContains(output, "generating")
        assertContains(output, "evaluating")
    }

    @Test
    fun usesMagentaBrandAndMatchingTaskSpinnerAndLegendMarkerColours() {
        val state = RunDashboardState()
            .reduce(RunEvent.PhaseChanged(proof, 1, SamplePhase.Generating))
            .reduce(RunEvent.PhaseChanged(statement, 1, SamplePhase.Evaluating))
        val rows = RunDashboard(context).render(state, 5.seconds, 160).render(terminal(160, colour = true), 160).lines
        assertEquals(TextColors.magenta.color, rows.first().single { it.text == "astrid" }.style.color)
        val taskRow = rows.indexOfFirst { row -> row.any { it.text == proof.value } }
        assertTrue(taskRow >= 0)
        assertEquals(listOf(TextColors.green.color, TextColors.blue.color),
            rows[taskRow].filter { it.text == "◢" }.map { it.style.color })
        assertEquals(listOf(TextColors.gray.color, TextColors.green.color, TextColors.blue.color, TextColors.yellow.color),
            rows[taskRow + 1].filter { it.text == "◤" }.map { it.style.color })
    }

    @Test
    fun keepsTasksOnOneLineAndSummarisesThoseThatDoNotFit() {
        val ids = (1..4).map { TaskId("task_1.${it}_theorem_11") }
        val metadata = context.copy(taskSubmissions = ids.associateWith { SubmissionKind("lean_proof_body") }, samplesPerTask = 1)
        val state = ids.fold(RunDashboardState()) { snapshot, id ->
            snapshot.reduce(RunEvent.PhaseChanged(id, 1, SamplePhase.Generating))
        }
        for (width in listOf(80, 160)) {
            val output = terminal(width).render(RunDashboard(metadata).render(state, 5.seconds, width))
            val taskRows = output.lines().filter { it.contains("task_1.") }
            assertEquals(1, taskRows.size)
            assertContains(taskRows.single(), "GENERATING")
            assertEquals(1, Regex("GENERATING").findAll(output).count())
            val visible = ids.count { taskRows.single().contains(it.value) }
            val hidden = Regex("\\+(\\d+) more").find(taskRows.single())?.groupValues?.get(1)?.toInt() ?: 0
            assertEquals(ids.size, visible + hidden)
            if (width == 160) assertEquals(ids.size, visible)
        }
    }
}
