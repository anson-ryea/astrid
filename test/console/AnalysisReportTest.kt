package console

import analysis.*
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import kotlinx.serialization.json.JsonObject
import run.RunTaskMetadata
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisReportTest {
    private fun analysis(id: String) = RunAnalyser().analyse(
        SavedRun(
            directory = Path.of("/runs/$id"),
            status = "failed",
            modelId = "test\u001b[2J\nmodel",
            samplesPerTask = 3,
            taskIds = (1..20).map { "task_1.${it}_claim" },
            taskMetadata = (1..20).associate {
                "task_1.${it}_claim" to
                        AnalysisTaskMetadata(RunTaskMetadata("paper_1", 1, "paper_evidence", "claim"), "saved_run")
            },
            samples = (1..20).flatMap { task ->
                listOf(
                    SavedSample("task_1.${task}_claim", 1, 6, SampleCategory.ACCEPTED, null, true, true, 0),
                    SavedSample("task_1.${task}_claim", 2, 6, SampleCategory.PROVIDER_FAILURE, null, false, false, 0),
                )
            },
            configuration = JsonObject(emptyMap()),
            warnings = listOf("Provisional saved-sample rates.\u001b[2J\nMissing samples are reported separately."),
        )
    )

    @Test
    fun wrapsWithoutClippingRunsTasksOrPoliciesAndHasNoControls() {
        val analyses = listOf(analysis("first-run"), analysis("last-run"))
        for (width in listOf(20, 40, 80, 120)) {
            val terminal =
                Terminal(width = width, terminalInterface = TerminalRecorder(width = width, ansiLevel = AnsiLevel.NONE))
            val output = terminal.render(AnalysisReport.render(analyses, width))
            assertTrue(output.lines().all { it.length <= width }, "Report overflowed width $width")
            val unwrapped = output.filterNot(Char::isWhitespace)
            for (text in listOf(
                "first-run",
                "last-run",
                "task_1.20_claim",
                "50.00%",
                "denominators",
                "No failure exclusions.",
                "Missing samples are reported separately."
            )) {
                assertContains(unwrapped, text.filterNot(Char::isWhitespace))
            }
            assertTrue(output.lines().size > 24)
            assertFalse(output.contains('\u001b'))
            assertFalse(output.contains("q quit"))
            assertFalse(output.contains("i dashboard"))
        }
    }

    @Test
    fun usesColoursWithoutCursorControlOrUntrustedEscapeSequences() {
        val terminal =
            Terminal(width = 100, terminalInterface = TerminalRecorder(width = 100, ansiLevel = AnsiLevel.TRUECOLOR))
        val output = terminal.render(AnalysisReport.render(listOf(analysis("coloured")), 100))
        assertContains(output, Regex("\u001b\\[[0-9;]*m"))
        assertContains(output, "test [2J model")
        assertFalse(output.contains("\u001b[2J"))
        assertFalse(output.contains("\u001b[?1049"))
        assertFalse(output.contains("\u001b[?25"))
    }
}
