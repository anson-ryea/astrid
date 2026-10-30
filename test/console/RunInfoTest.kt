package console

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import domain.SubmissionKind
import domain.TaskId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import run.BenchmarkRunConfig

class RunInfoTest {
    private val ids = listOf(TaskId("task_1.2_theorem_11"), TaskId("task_1.10_theorem_11"), TaskId("task_2.1_theorem_11"))
    private val context = RunDashboardContext(
        "proofs-01", "test-model", Path.of("/datasets/pl-suite"),
        ids.associateWith { SubmissionKind("lean_proof_body") }, 2,
        configPath = Path.of("/configs/proofs.json"), outputPath = Path.of("/runs/proofs-01"), backendId = "lean",
        runConfig = BenchmarkRunConfig("test-model", null, 4096, false, 2, null, 10, maxConcurrentTasks = 3),
        taskProblems = ids.associateWith { RunTaskProblem(if (it == ids.last()) "paper_2" else "paper_1", "shared_problem") },
    )

    private fun terminal(width: Int) = Terminal(width = width,
        terminalInterface = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = width))

    @Test
    fun showsValidatedSettingsAndPathsWithTasksGroupedByPaperAndProblem() {
        val output = terminal(120).render(RunInfo(context).render(120, 70, true))
        assertEquals("info", output.lines().first().trim())
        assertContains(output.lines()[1], "─")
        assertEquals("run", output.lines()[2].trim())
        for (value in listOf("proofs-01", "RUNNING", "test-model", "lean", "/configs/proofs.json", "/datasets/pl-suite",
            "/runs/proofs-01", "concurrent tasks", "provider default", "4096", "unlimited", "workspace tool calls")) {
            assertContains(output, value)
        }
        assertContains(output, "├─ paper_1")
        assertContains(output, "└─ paper_2")
        assertEquals(2, Regex("shared_problem").findAll(output).count())
        assertContains(output, "│  └─ shared_problem")
        assertContains(output, "│     ├─ ${ids[0]}")
        assertContains(output, "│     └─ ${ids[1]}")
        assertTrue(output.indexOf(ids[0].value) < output.indexOf(ids[1].value))
        assertContains(output.lines().last(), "i dashboard")
        assertContains(output.lines().last(), "q quit")
    }

    @Test
    fun scrollsToTheTaskListAndBackWithoutMovingTheHeaderOrOverflowingTheScreen() {
        val info = RunInfo(context.copy(runId = "proofs\u001b[2J\ninjected"))
        fun render() = terminal(80).render(info.render(80, 8, true))
        val start = render()
        assertFalse(start.contains('\u001b'))
        assertContains(start, "proofs [2J injected")
        info.scrollBy(3)
        assertFalse(render() == start)
        info.end()
        val end = render()
        assertEquals("info", end.lines().first().trim())
        assertContains(end, ids.last().value)
        assertContains(end.lines().last(), "q quit")
        info.home()
        assertEquals(start, render())
        for (width in listOf(20, 40, 80, 160)) for (height in listOf(2, 3, 4, 8, 24)) {
            val frame = info.render(width, height, true)
            assertTrue(frame.render(terminal(width), width).height <= height - 1)
            assertTrue(terminal(width).render(frame).lines().all { it.length <= width })
        }
    }
}
