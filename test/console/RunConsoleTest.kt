package console

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import com.github.ajalt.mordant.terminal.TerminalInterface
import com.github.ajalt.mordant.terminal.PrintRequest
import domain.EvaluationResult
import domain.SubmissionKind
import domain.TaskId
import java.nio.file.Path
import java.nio.file.Files
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import run.RunEvent
import run.SamplePhase
import run.RunPhase
import support.TerminalScreen
import org.junit.jupiter.api.io.TempDir

class RunConsoleTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val context = RunDashboardContext(
        "proofs-01", "test-model", Path.of("/datasets/pl-suite"),
        mapOf(TaskId("task_1.1_theorem_11") to SubmissionKind("lean_proof_body")), 1,
    )

    @Test
    fun redirectedOutputPrintsOneFinalFrameAndUsesElapsedTime() = runBlocking {
        val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 100)
        val clock = TestTimeSource()
        val console = RunConsole(Terminal(terminalInterface = recorder), context, clock.markNow())
        val result = console.run {
            clock += 65.seconds
            assertEquals("", recorder.output())
            "result"
        }
        assertEquals("result", result)
        assertContains(recorder.output(), "00:01:05")
        assertContains(recorder.output(), "COMPLETE")
        assertFalse(recorder.output().contains('\u001b'))
        assertEquals(1, Regex("astrid", RegexOption.IGNORE_CASE).findAll(recorder.output()).count())
    }

    @Test
    fun failuresAndCancellationRenderTheirStatusAndPropagate() = runBlocking {
        for (failure in listOf(IllegalStateException("disk unavailable"), CancellationException("cancelled"))) {
            val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 100)
            val console = RunConsole(Terminal(terminalInterface = recorder), context)
            val thrown = assertFailsWith<Exception> {
                console.run { throw failure }
            }
            assertEquals(failure.message, thrown.message)
            assertContains(recorder.output(), if (failure is CancellationException) "CANCELLED" else "FAILED")
        }
    }

    @Test
    fun interactiveAnimationStopsBeforeLaterTerminalOutput() = runBlocking {
        val recorder = TerminalRecorder(width = 120)
        val terminal = Terminal(terminalInterface = recorder)
        val clock = TestTimeSource()
        RunConsole(terminal, context, clock.markNow()).run { onEvent ->
            assertContains(recorder.output(), "PREPARING")
            yield()
            onEvent(RunEvent.PhaseChanged(context.taskSubmissions.keys.single(), 1, SamplePhase.Generating))
            clock += 2.seconds
            withTimeout(3.seconds) {
                while (!recorder.output().contains("00:00:02")) delay(10.milliseconds)
            }
            assertContains(recorder.output(), "GENERATING")
            assertContains(recorder.output(), "◐")
            // The task state stays unchanged while Mordant redraws successive spinner frames.
            for (frame in listOf("◓", "◑", "◒")) {
                clock += 250.milliseconds
                withTimeout(3.seconds) {
                    while (!recorder.output().contains(frame)) delay(10.milliseconds)
                }
            }
        }
        assertContains(recorder.output(), "COMPLETE")
        recorder.clearOutput()
        terminal.println("After the run")
        assertTrue(recorder.output().endsWith("After the run\n"))
        assertFalse(recorder.output().contains("Astrid"))
    }

    @Test
    fun forcedLiveModeAnimatesEvenWhenTheOutputIsReportedAsNonInteractive() = runBlocking {
        val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 100)
        val terminal = Terminal(terminalInterface = recorder)
        RunConsole(terminal, context, mode = RunConsoleMode.Live).run { onEvent ->
            assertContains(recorder.output(), "PREPARING")
            assertContains(recorder.output(), "\u001b[")
            onEvent(RunEvent.SampleStarted(context.taskSubmissions.keys.single(), 1))
            onEvent(RunEvent.SampleFinished(context.taskSubmissions.keys.single(), 1, EvaluationResult.Accepted))
        }
        assertContains(recorder.output(), "1/1 tasks")
        assertContains(recorder.output(), "COMPLETE")
        assertTrue(recorder.output().endsWith("\u001b[?25h"))
    }

    @Test
    fun avoidsRepeatedFramesWhenTheConsoleCannotMoveUp() = runBlocking {
        for (mode in listOf(RunConsoleMode.Auto, RunConsoleMode.Live)) {
            // In Mordant 3.1 this is the flag set by the IntelliJ Run console detector.
            val recorder = TerminalRecorder(width = 120, supportsAnsiCursor = true)
            RunConsole(Terminal(terminalInterface = recorder), context, mode = mode).run { onEvent ->
                onEvent(RunEvent.SampleStarted(context.taskSubmissions.keys.single(), 1))
                onEvent(RunEvent.SampleFinished(context.taskSubmissions.keys.single(), 1, EvaluationResult.Accepted))
            }
            assertEquals(1, Regex("astrid", RegexOption.IGNORE_CASE).findAll(recorder.output()).count())
            assertFalse(recorder.output().contains('\u001b'))
            assertContains(recorder.output(), "Finished")
            if (mode == RunConsoleMode.Live) assertContains(recorder.output(), "Use a terminal tab")
        }
    }

    @Test
    fun refreshingAShortTerminalDoesNotScrollHeadersIntoHistory() = runBlocking {
        val ids = (1..4).map { TaskId("task_1.${it}_theorem_11") }
        val recorder = TerminalRecorder(width = 120, height = 8)
        val clock = TestTimeSource()
        val metadata = context.copy(taskSubmissions = ids.associateWith { SubmissionKind("lean_proof_body") })
        RunConsole(Terminal(terminalInterface = recorder), metadata, clock.markNow()).run { onEvent ->
            ids.forEach { onEvent(RunEvent.PhaseChanged(it, 1, SamplePhase.Generating)) }
            for (second in 1..3) {
                clock += 1.seconds
                withTimeout(3.seconds) {
                    while (!recorder.output().contains("00:00:0$second")) delay(10.milliseconds)
                }
            }
        }
        val screen = TerminalScreen(120, 8)
        screen.write(recorder.output())
        assertTrue(screen.history.none { it.contains("astrid", ignoreCase = true) }, "old headers were scrolled into history")
        assertEquals(1, screen.lines.count { it.contains("astrid", ignoreCase = true) })
        assertContains(screen.lines.joinToString("\n"), "COMPLETE")
    }

    @Test
    fun coldLoggingInitialisationDoesNotLeaveThePreparingFrameAboveTheRunningFrame() {
        val classpath = buildSet {
            addAll(System.getProperty("java.class.path").split(File.pathSeparator))
            generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>().forEach { loader ->
                loader.urLs.filter { it.protocol == "file" }.forEach { add(Path.of(it.toURI()).toString()) }
            }
            add(Path.of(javaClass.protectionDomain.codeSource.location.toURI()).toString())
            add(Path.of(RunConsole::class.java.protectionDomain.codeSource.location.toURI()).toString())
        }.joinToString(File.pathSeparator)
        val log = temporaryDirectory.resolve("cold-logging.txt")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED", "-cp", classpath, "support.RunConsoleLoggingProbe",
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "logging probe did not finish")
            val output = Files.readString(log)
            assertEquals(0, process.exitValue(), output)
            assertFalse(output.contains("kotlin-logging: initializing"), output)
            assertTrue(output.lastIndexOf("SLF4J") < output.indexOf("astrid"),
                "logging diagnostics appeared after the first frame: $output")
            val screen = TerminalScreen(120, 12)
            screen.write(output)
            assertTrue(screen.history.none { it.contains("astrid", ignoreCase = true) }, output)
            assertEquals(1, screen.lines.count { it.contains("astrid", ignoreCase = true) }, output)
            assertFalse(screen.lines.any { it.contains("PREPARING") }, output)
            assertContains(screen.lines.joinToString("\n"), "COMPLETE")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test
    fun plainModePrintsEveryMilestoneAndDrainsResultsBeforeTheFinalFrame() = runBlocking {
        val recorder = TerminalRecorder(width = 120)
        val task = context.taskSubmissions.keys.single()
        RunConsole(Terminal(terminalInterface = recorder), context.copy(samplesPerTask = 100),
            mode = RunConsoleMode.Plain).run { onEvent ->
            onEvent(RunEvent.RunPhaseChanged(RunPhase.Preparing))
            onEvent(RunEvent.RunPhaseChanged(RunPhase.ResolvingModel))
            onEvent(RunEvent.RunPhaseChanged(RunPhase.Running))
            for (sample in 1..100) {
                onEvent(RunEvent.SampleStarted(task, sample))
                onEvent(RunEvent.SampleFinished(task, sample, EvaluationResult.Accepted))
            }
        }
        val output = recorder.output()
        assertFalse(output.contains('\u001b'))
        assertContains(output, "Preparing tasks")
        assertContains(output, "Resolving model limits")
        assertEquals(100, Regex("Finished $task").findAll(output).count())
        assertTrue(output.indexOf("Finished $task · sample 100: success") < output.indexOf("COMPLETE"))
        assertContains(output, "100 success")
        assertContains(output, "100/100 samples")
        assertContains(output, "1/1 tasks")
    }

    @Test
    fun scopeCancellationStopsAnimationAndRestoresTheCursor() = runBlocking {
        val recorder = TerminalRecorder(width = 120)
        val terminal = Terminal(terminalInterface = recorder)
        val started = CompletableDeferred<Unit>()
        withTimeout(3.seconds) {
            val running = launch {
                RunConsole(terminal, context).run {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            running.cancelAndJoin()
        }
        assertContains(recorder.output(), "CANCELLED")
        assertTrue(recorder.output().endsWith("\u001b[?25h"))
        recorder.clearOutput()
        terminal.println("After cancellation")
        assertEquals("After cancellation\n", recorder.output())
    }

    @Test
    fun preservesTheExecutionFailureWhenFinalRenderingAlsoFails() = runBlocking {
        val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE)
        val brokenOutput = object : TerminalInterface by recorder {
            override fun completePrintRequest(request: PrintRequest) {
                error("terminal unavailable")
            }
        }
        val console = RunConsole(Terminal(terminalInterface = brokenOutput), context)
        val failure = assertFailsWith<IllegalArgumentException> {
            console.run { throw IllegalArgumentException("benchmark failed") }
        }
        assertEquals("benchmark failed", failure.message)
        // Stack recovery can copy a coroutine exception, retaining the original as its cause.
        assertTrue(generateSequence(failure as Throwable) { it.cause }
            .flatMap { it.suppressed.asSequence() }.any { it.message == "terminal unavailable" })
    }
}
