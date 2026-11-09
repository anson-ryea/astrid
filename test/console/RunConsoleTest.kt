package console

import com.github.ajalt.mordant.input.InputEvent
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.*
import dataset.TaskRepository
import domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import run.*
import support.TerminalScreen
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark

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
            onEvent(
                RunEvent.SampleFinished(
                    context.taskSubmissions.keys.single(),
                    1,
                    SampleOutcome.Evaluated(EvaluationResult.Accepted)
                )
            )
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
                onEvent(
                    RunEvent.SampleFinished(
                        context.taskSubmissions.keys.single(),
                        1,
                        SampleOutcome.Evaluated(EvaluationResult.Accepted)
                    )
                )
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
        assertTrue(
            screen.history.none { it.contains("astrid", ignoreCase = true) },
            "old headers were scrolled into history"
        )
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
            assertTrue(
                output.lastIndexOf("SLF4J") < output.indexOf("astrid"),
                "logging diagnostics appeared after the first frame: $output"
            )
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
        RunConsole(
            Terminal(terminalInterface = recorder), context.copy(samplesPerTask = 100),
            mode = RunConsoleMode.Plain
        ).run { onEvent ->
            onEvent(RunEvent.RunPhaseChanged(RunPhase.Preparing))
            onEvent(RunEvent.RunPhaseChanged(RunPhase.ResolvingModel))
            onEvent(RunEvent.RunPhaseChanged(RunPhase.Running))
            for (sample in 1..100) {
                onEvent(RunEvent.SampleStarted(task, sample))
                onEvent(RunEvent.SampleFinished(task, sample, SampleOutcome.Evaluated(EvaluationResult.Accepted)))
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
    fun logShortcutTogglesPagesAndKeepsFollowingMilestonesWithoutScrollingOldHeaders() = runBlocking {
        val recorder = TerminalRecorder(width = 120, height = 10)
        val input = KeyboardTerminal(recorder)
        val task = context.taskSubmissions.keys.single()
        fun screen() = TerminalScreen(120, 10).also { it.write(recorder.output()) }
        suspend fun waitFor(text: String) = withTimeout(3.seconds) {
            while (!screen().lines.joinToString("\n").contains(text)) delay(10.milliseconds)
        }
        RunConsole(Terminal(terminalInterface = input), context).run { onEvent ->
            assertTrue(input.rawMode.get())
            onEvent(RunEvent.RunPhaseChanged(RunPhase.Preparing))
            onEvent(RunEvent.SampleStarted(task, 1))
            withContext(Dispatchers.IO) {
                input.keys.put(KeyboardEvent("l"))
            }
            waitFor("l dashboard")
            val logs = screen().lines.joinToString("\n")
            assertContains(logs, "Preparing tasks")
            assertContains(logs, "Started $task · sample 1")
            assertTrue(Regex("\\d{2}:\\d{2}:\\d{2} {2}◇ Started").containsMatchIn(logs))
            assertEquals("logs", screen().lines.first().trim())
            assertFalse(logs.contains("astrid"))
            withContext(Dispatchers.IO) {
                input.keys.put(KeyboardEvent("l"))
            }
            waitFor("l logs")
            onEvent(RunEvent.SampleFinished(task, 1, SampleOutcome.Evaluated(EvaluationResult.Accepted)))
            waitFor("1 success")
            withContext(Dispatchers.IO) {
                input.keys.put(KeyboardEvent("l"))
            }
            waitFor("l dashboard")
            waitFor("Finished $task · sample 1: success")
            val logsAgain = screen().lines.joinToString("\n")
            assertTrue(logsAgain.indexOf("Started") < logsAgain.indexOf("Finished"))
            assertEquals(1, Regex("Started $task").findAll(logsAgain).count())
        }
        assertFalse(input.rawMode.get())
        assertContains(screen().lines.joinToString("\n"), "COMPLETE: 1 samples saved")
        assertTrue(screen().history.none { it.contains("astrid") || it.trim() == "logs" })
        assertTrue(recorder.output().endsWith("\u001b[?25h"))
    }

    @Test
    fun ctrlCInRawModeCancelsTheRunAndRestoresInput() = runBlocking {
        val recorder = TerminalRecorder(width = 120)
        val input = KeyboardTerminal(recorder)
        assertFailsWith<CancellationException> {
            RunConsole(Terminal(terminalInterface = input), context).run {
                withContext(Dispatchers.IO) {
                    input.keys.put(KeyboardEvent("c", ctrl = true))
                }
                awaitCancellation()
            }
        }
        assertFalse(input.rawMode.get())
        assertContains(recorder.output(), "CANCELLED")
        assertTrue(recorder.output().endsWith("\u001b[?25h"))
    }

    @Test
    fun quitKeysShowQuittingOnEveryPageUntilCleanupFinishes() = runBlocking {
        for (page in RunConsolePage.entries) for (key in listOf(
            KeyboardEvent("q"), KeyboardEvent("c", ctrl = true), KeyboardEvent("Q"), KeyboardEvent("q", shift = true),
        )) {
            val recorder = TerminalRecorder(width = 120)
            val input = KeyboardTerminal(recorder)
            val clock = TestTimeSource()
            val started = CompletableDeferred<Unit>()
            val cleaningUp = CompletableDeferred<Unit>()
            val finishCleanup = CompletableDeferred<Unit>()
            fun screen() = TerminalScreen(120, 24).also { it.write(recorder.output()) }.lines.joinToString("\n")
            suspend fun waitFor(text: String) = withTimeout(3.seconds) {
                while (!screen().contains(text)) delay(10.milliseconds)
            }

            val running = async {
                runCatching {
                    RunConsole(Terminal(terminalInterface = input), context, clock.markNow()).run {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleaningUp.complete(Unit)
                                finishCleanup.await()
                            }
                        }
                    }
                }
            }
            try {
                started.await()
                when (page) {
                    RunConsolePage.Dashboard -> Unit
                    RunConsolePage.Logs -> {
                        withContext(Dispatchers.IO) {
                            input.keys.put(KeyboardEvent("l"))
                        }; waitFor("l dashboard")
                    }

                    RunConsolePage.Info -> {
                        withContext(Dispatchers.IO) {
                            input.keys.put(KeyboardEvent("i"))
                        }; waitFor("i dashboard")
                    }
                }
                withContext(Dispatchers.IO) {
                    input.keys.put(key)
                }
                withTimeout(3.seconds) { cleaningUp.await() }
                assertContains(screen(), "QUITTING")
                assertFalse(screen().contains("CANCELLED"))
                assertFalse(screen().contains("q quit"))
                // A later periodic refresh must retain QUITTING while cleanup is still blocked.
                clock += 1.seconds
                if (page == RunConsolePage.Dashboard) waitFor("00:00:01") else delay(300.milliseconds)
                assertContains(screen(), "QUITTING")
                finishCleanup.complete(Unit)
                val result = withTimeout(3.seconds) { running.await() }
                assertTrue(result.exceptionOrNull() is CancellationException)
                if (key.key.equals("q", ignoreCase = true)) {
                    assertEquals(
                        key.key == "Q" || key.shift,
                        (result.exceptionOrNull() as RunQuitException).discardResults
                    )
                }
                assertContains(screen(), "CANCELLED")
                assertFalse(screen().contains("Quitting… waiting for cleanup"))
                if (page != RunConsolePage.Logs) assertFalse(screen().contains("QUITTING"))
                assertFalse(input.rawMode.get())
                assertTrue(recorder.output().endsWith("\u001b[?25h"))
            } finally {
                finishCleanup.complete(Unit)
                running.cancelAndJoin()
            }
        }
    }

    @Test
    fun infoShortcutNavigatesAndScrollsWhileExecutionContinues() = runBlocking {
        val recorder = TerminalRecorder(width = 120, height = 10)
        val input = KeyboardTerminal(recorder)
        val task = context.taskSubmissions.keys.single()
        fun screen() = TerminalScreen(120, 10).also { it.write(recorder.output()) }
        suspend fun waitFor(text: String) = withTimeout(3.seconds) {
            while (!screen().lines.joinToString("\n").contains(text)) delay(10.milliseconds)
        }
        RunConsole(Terminal(terminalInterface = input), context).run { onEvent ->
            withContext(Dispatchers.IO) {
                input.keys.put(KeyboardEvent("i"))
            }
            waitFor("i dashboard")
            assertEquals("info", screen().lines.first().trim())
            withContext(Dispatchers.IO) {
                input.keys.put(KeyboardEvent("End"))
            }
            waitFor(task.value)
            withContext(Dispatchers.IO) {
                input.keys.put(KeyboardEvent("Home"))
            }
            waitFor("proofs-01")
            onEvent(RunEvent.SampleFinished(task, 1, SampleOutcome.Evaluated(EvaluationResult.Accepted)))
            withContext(Dispatchers.IO) {
                input.keys.put(KeyboardEvent("l"))
            }
            waitFor("l dashboard")
            waitFor("Finished $task")
            withContext(Dispatchers.IO) {
                input.keys.put(KeyboardEvent("i"))
            }
            waitFor("i dashboard")
            withContext(Dispatchers.IO) {
                input.keys.put(KeyboardEvent("i"))
            }
            waitFor("l logs")
            waitFor("1 success")
        }
        assertTrue(screen().history.none { it.contains("astrid") || it.trim() == "info" || it.trim() == "logs" })
        assertFalse(input.rawMode.get())
    }

    @Test
    fun quitFromEveryPageStopsWorkersBeforeKeepingOrDiscardingResults() = runBlocking {
        for (page in RunConsolePage.entries) for (discard in listOf(false, true)) {
            val root = withContext(Dispatchers.IO) {
                Files.createDirectory(temporaryDirectory.resolve("${page.name}-$discard"))
            }
            val task = createRunTask(root)
            val runDirectory = root.resolve("run")
            val sampleDirectory = runDirectory.resolve("tasks/${task.manifest.taskId}/samples/001")
            val recorder = TerminalRecorder(width = 120)
            val input = KeyboardTerminal(recorder)
            val stopped = AtomicBoolean(false)
            var savedRecord = ""
            var savedTrajectory = ""
            val executor = ScriptedPromptExecutor(listOf("proof", "wait", "unused")) { reply ->
                if (reply == "proof") ai.koog.prompt.message.Message.Assistant(
                    reply, ai.koog.prompt.message.ResponseMetaInfo.Empty,
                ) else {
                    savedRecord = Files.readString(sampleDirectory.resolve("record.json"))
                    savedTrajectory = Files.readString(sampleDirectory.resolve("trajectory.jsonl"))
                    if (page == RunConsolePage.Logs) input.keys.put(KeyboardEvent("l"))
                    if (page == RunConsolePage.Info) input.keys.put(KeyboardEvent("i"))
                    input.keys.put(KeyboardEvent(if (discard) "Q" else "q"))
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.set(true)
                    }
                }
            }
            val repository = object : TaskRepository {
                override fun findAll() = listOf(task)
                override fun findById(taskId: TaskId): BenchmarkTask? = task.takeIf { it.manifest.taskId == taskId }
            }
            val metadata = context.copy(
                taskSubmissions = mapOf(task.manifest.taskId to task.manifest.submission.kind), samplesPerTask = 3,
            )
            val engine =
                createRunEngine(root, executor, runTestSpecification(samplesPerTask = 3, maxConcurrentTasks = 1))
            val store = FileRunStore(runDirectory)
            withTimeout(10.seconds) {
                val failure = assertFailsWith<RunQuitException> {
                    RunConsole(Terminal(terminalInterface = input), metadata).run { onEvent ->
                        BenchmarkRunner(
                            repository, TaskSelector(setOf(EvaluationKind("test_evaluation"))),
                            engine, store, onEvent = onEvent
                        ).run()
                    }
                }
                assertEquals(discard, failure.discardResults)
            }
            assertTrue(stopped.get())
            assertEquals(2, executor.prompts.size)
            assertEquals(savedRecord, withContext(Dispatchers.IO) {
                Files.readString(sampleDirectory.resolve("record.json"))
            })
            assertEquals(savedTrajectory, withContext(Dispatchers.IO) {
                Files.readString(sampleDirectory.resolve("trajectory.jsonl"))
            })
            assertFalse(Files.exists(sampleDirectory.resolveSibling("002/record.json")))
            assertFalse(Files.exists(sampleDirectory.resolveSibling("003/record.json")))
            val manifest = Json.parseToJsonElement(withContext(Dispatchers.IO) {
                Files.readString(runDirectory.resolve("run.json"))
            }).jsonObject
            assertEquals("failed", manifest.getValue("status").jsonPrimitive.content)
            assertContains(manifest.getValue("failure_reason").jsonPrimitive.content, "stopped by user")
            assertFalse(input.rawMode.get())
            val screen = TerminalScreen(120, 24).also { it.write(recorder.output()) }.lines.joinToString("\n")
            assertContains(screen, "CANCELLED")
            when (page) {
                RunConsolePage.Logs -> assertContains(
                    screen,
                    if (discard) "Run output will be discarded" else "1 samples saved"
                )

                RunConsolePage.Dashboard -> assertContains(screen, "1 success")
                RunConsolePage.Info -> assertContains(screen, "info")
            }
            assertFalse(screen.contains("COMPLETE"))
            assertTrue(recorder.output().endsWith("\u001b[?25h"))
            if (discard) {
                assertTrue(store.discard())
                assertFalse(Files.exists(runDirectory))
            } else assertTrue(Files.exists(runDirectory))
        }
    }

    @Test
    fun missingRawInputSupportKeepsTheDashboardWithoutAdvertisingTheShortcut() = runBlocking {
        val recorder = TerminalRecorder(width = 120)
        val input = object : TerminalInterface by recorder {
            override fun enterRawMode(mouseTracking: MouseTracking): AutoCloseable =
                throw UnsupportedOperationException("no keyboard")
        }
        RunConsole(Terminal(terminalInterface = input), context).run {
            assertContains(recorder.output(), "PREPARING")
            assertFalse(recorder.output().contains(" logs"))
        }
        assertContains(recorder.output(), "COMPLETE")
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

    private class KeyboardTerminal(recorder: TerminalRecorder) : TerminalInterface by recorder {
        val keys = LinkedBlockingQueue<InputEvent>()
        val rawMode = AtomicBoolean(false)

        override fun enterRawMode(mouseTracking: MouseTracking): AutoCloseable {
            rawMode.set(true)
            return AutoCloseable { rawMode.set(false) }
        }

        override fun readInputEvent(timeout: TimeMark, mouseTracking: MouseTracking): InputEvent =
            keys.poll(timeout.elapsedNow().unaryMinus().inWholeMilliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                ?: throw TimeoutException()
    }
}
