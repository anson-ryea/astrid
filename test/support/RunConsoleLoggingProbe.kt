package support

import com.github.ajalt.mordant.terminal.PrintRequest
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalInterface
import com.github.ajalt.mordant.terminal.TerminalRecorder
import console.RunConsole
import console.RunDashboardContext
import domain.EvaluationResult
import domain.SubmissionKind
import domain.TaskId
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import run.RunEvent
import run.RunPhase
import run.SampleOutcome
import run.SamplePhase
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Runs in a fresh JVM so logging initialisation cannot be hidden by earlier tests. */
internal object RunConsoleLoggingProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        check(KotlinLoggingConfiguration.logStartupMessage) { "expected a fresh logging configuration" }
        val recorder = TerminalRecorder(width = 120, height = 12)
        val output = object : TerminalInterface by recorder {
            override fun completePrintRequest(request: PrintRequest) {
                recorder.completePrintRequest(request)
                print(request.text)
                if (request.trailingLinebreak) println()
            }
        }
        val task = TaskId("task_1.1_theorem_11")
        val context = RunDashboardContext(
            "logging-probe", "test-model", Path.of("/datasets/probe"),
            mapOf(task to SubmissionKind("lean_proof_body")), 1
        )
        val clock = TestTimeSource()
        RunConsole(Terminal(terminalInterface = output), context, clock.markNow()).run { onEvent ->
            // This is the first logger requested by execution code, after the dashboard is visible.
            KotlinLogging.logger("koog.probe")
            onEvent(RunEvent.RunPhaseChanged(RunPhase.Running))
            onEvent(RunEvent.SampleStarted(task, 1))
            onEvent(RunEvent.PhaseChanged(task, 1, SamplePhase.Generating))
            clock += 1.seconds
            withTimeout(3.seconds) {
                while (!recorder.output().contains("GENERATING")) delay(10.milliseconds)
            }
            onEvent(RunEvent.PhaseChanged(task, 1, SamplePhase.Evaluating))
            clock += 1.seconds
            withTimeout(3.seconds) {
                while (!recorder.output().contains("EVALUATING")) delay(10.milliseconds)
            }
            onEvent(RunEvent.SampleFinished(task, 1, SampleOutcome.Evaluated(EvaluationResult.Accepted)))
        }
    }
}
