package run

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import console.RunEventProcessor
import console.RunConsole
import console.RunConsoleMode
import console.RunDashboardContext
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import dataset.TaskRepository
import domain.BenchmarkTask
import domain.EvaluationKind
import domain.EvaluationResult
import domain.TaskId
import domain.TaskStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertContains
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import kotlin.time.Duration.Companion.milliseconds

class BenchmarkRunnerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun savesEveryIndependentSampleBeforeCompleting() {
        runBlocking {
            val first = createRunTask(temporaryDirectory)
            val second = createRunTask(
                temporaryDirectory,
                id = "task_1.2_theorem_11",
            )
            val executor = ScriptedPromptExecutor(List(4) { "proof" })
            val store = RecordingRunStore()
            val events = mutableListOf<RunEvent>()
            val runner = runner(
                tasks = listOf(second, first),
                executor = executor,
                store = store,
                specification = runTestSpecification(samplesPerTask = 2, maxConcurrentTasks = 1),
                onEvent = { event ->
                    if (event is RunEvent.SampleFinished) {
                        assertTrue(store.records.any {
                            it.taskId == event.taskId && it.sampleIndex == event.sampleIndex && it.finalResult == event.result
                        })
                    }
                    events += event
                },
            )

            runner.run()

            assertEquals(
                listOf(
                    "begin",
                    "save:${first.manifest.taskId}:1",
                    "save:${first.manifest.taskId}:2",
                    "save:${second.manifest.taskId}:1",
                    "save:${second.manifest.taskId}:2",
                    "complete",
                ),
                store.events,
            )
            assertEquals(listOf(first.manifest.taskId, second.manifest.taskId), store.taskIds)
            assertEquals(4, executor.prompts.size)
            assertTrue(store.records.all { it.finalResult == EvaluationResult.Accepted })
            assertEquals(listOf(first, second).flatMap { task ->
                (1..2).flatMap { sample ->
                    listOf(
                        RunEvent.SampleStarted(task.manifest.taskId, sample),
                        RunEvent.PhaseChanged(task.manifest.taskId, sample, SamplePhase.Preparing),
                        RunEvent.PhaseChanged(task.manifest.taskId, sample, SamplePhase.Generating),
                        RunEvent.PhaseChanged(task.manifest.taskId, sample, SamplePhase.Evaluating),
                        RunEvent.SampleFinished(task.manifest.taskId, sample, EvaluationResult.Accepted),
                    )
                }
            }, events)
        }
    }

    @Test
    fun verifiesAllPackagesBeforeStartingTheRun() {
        runBlocking {
            val valid = createRunTask(temporaryDirectory)
            val changed = createRunTask(
                temporaryDirectory,
                id = "task_1.2_theorem_11",
            )
            Files.writeString(
                changed.packageDirectory.resolve("Context.txt"),
                "changed after hashing",
            )
            val executor = ScriptedPromptExecutor(listOf("unused"))
            val store = RecordingRunStore()

            assertFailsWith<IllegalStateException> {
                runner(listOf(valid, changed), executor, store).run()
            }

            assertTrue(store.events.isEmpty())
            assertTrue(executor.prompts.isEmpty())
        }
    }

    @Test
    fun continuesAfterEverySampleOfATaskHasAnExecutorFailure() {
        runBlocking {
            val task = createRunTask(temporaryDirectory)
            val next = createRunTask(temporaryDirectory, id = "task_1.2_theorem_11")
            val executor = ScriptedPromptExecutor(listOf("fail", "fail", "proof", "proof")) {
                check(it != "fail") { "provider unavailable" }
                Message.Assistant(it, ResponseMetaInfo.Empty)
            }
            val store = RecordingRunStore()
            val runner = runner(
                tasks = listOf(task, next),
                executor = executor,
                store = store,
                specification = runTestSpecification(samplesPerTask = 2, maxConcurrentTasks = 1),
            )

            runner.run()

            assertEquals(listOf("begin", "save:${task.manifest.taskId}:1", "save:${task.manifest.taskId}:2",
                "save:${next.manifest.taskId}:1", "save:${next.manifest.taskId}:2", "complete"), store.events)
            store.records.take(2).forEach { assertIs<EvaluationResult.InfrastructureFailure>(it.finalResult) }
            assertTrue(store.records.drop(2).all { it.finalResult == EvaluationResult.Accepted })
            assertNull(store.failureReason)
            assertEquals(4, executor.prompts.size)
        }
    }

    @Test
    fun completesEvenWhenEverySampleRequiresReview() {
        runBlocking {
            val task = createRunTask(temporaryDirectory)
            val next = createRunTask(temporaryDirectory, id = "task_1.2_theorem_11")
            val executor = ScriptedPromptExecutor(List(4) { "proposal" })
            val store = RecordingRunStore()
            val runner = runner(
                tasks = listOf(task, next),
                executor = executor,
                store = store,
                specification = runTestSpecification(samplesPerTask = 2, maxConcurrentTasks = 1),
                assessSubmission = { _, _ -> EvaluationResult.NeedsReview("human check") },
            )

            runner.run()

            assertEquals(4, store.records.size)
            store.records.forEach { assertIs<EvaluationResult.NeedsReview>(it.finalResult) }
            assertEquals("complete", store.events.last())
            assertNull(store.failureReason)
            assertEquals(4, executor.prompts.size)
        }
    }

    @Test
    fun continuesAfterEverySampleAndRepairOfATaskIsRejected() = runBlocking {
        val task = createRunTask(temporaryDirectory)
        val next = createRunTask(temporaryDirectory, id = "task_1.2_theorem_11")
        val executor = ScriptedPromptExecutor(List(6) { "proof" })
        val store = RecordingRunStore()

        runner(listOf(task, next), executor, store,
            specification = runTestSpecification(samplesPerTask = 2, maxConcurrentTasks = 1),
            assessSubmission = { current, _ ->
                if (current.manifest.taskId == task.manifest.taskId) EvaluationResult.Rejected("invalid proof")
                else EvaluationResult.Accepted
            }).run()

        assertEquals(4, store.records.size)
        store.records.take(2).forEach {
            assertIs<EvaluationResult.Rejected>(it.finalResult)
            assertEquals(2, it.turns.size)
        }
        assertTrue(store.records.drop(2).all { it.finalResult == EvaluationResult.Accepted })
        assertEquals(6, executor.prompts.size)
        assertEquals("complete", store.events.last())
        assertNull(store.failureReason)
    }

    @Test
    fun runsTasksOnMultipleThreadsWithinTheConfiguredLimit() = runBlocking {
        val events = ConcurrentLinkedQueue<RunEvent>()
        val tasks = (1..6).map { createRunTask(temporaryDirectory, id = "task_1.${it}_theorem_11") }
        val barrier = CyclicBarrier(2)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val threads = ConcurrentHashMap.newKeySet<String>()
        val executor = ScriptedPromptExecutor(List(12) { "proof" }) {
            threads += Thread.currentThread().name
            val count = active.incrementAndGet()
            peak.accumulateAndGet(count, ::maxOf)
            try {
                // Blocking both calls proves that tasks use separate threads, not just suspension.
                barrier.await(10, TimeUnit.SECONDS)
                Message.Assistant(it, ResponseMetaInfo.Empty)
            } finally {
                active.decrementAndGet()
            }
        }
        val store = RecordingRunStore()

        withTimeout(30_000.milliseconds) {
            runner(tasks, executor, store,
                specification = runTestSpecification(samplesPerTask = 2, maxConcurrentTasks = 2),
                onEvent = { events.add(it) }).run()
        }

        assertEquals(2, peak.get())
        assertTrue(threads.size >= 2)
        assertEquals(12, store.records.size)
        assertTrue(store.records.all { it.finalResult == EvaluationResult.Accepted })
        for (task in tasks) {
            assertEquals(listOf(1, 2), store.records.filter { it.taskId == task.manifest.taskId }.map { it.sampleIndex })
            for (sample in 1..2) {
                val started = RunEvent.SampleStarted(task.manifest.taskId, sample)
                val finished = RunEvent.SampleFinished(task.manifest.taskId, sample, EvaluationResult.Accepted)
                assertEquals(1, events.count { it == started })
                assertEquals(1, events.count { it == finished })
                assertTrue(events.indexOf(started) < events.indexOf(finished))
            }
        }
        assertEquals("begin", store.events.first())
        assertEquals("complete", store.events.last())
    }

    @Test
    fun persistsEveryConcurrentSampleIncludingFailuresBeforeCompleting() = runBlocking {
        val tasks = (1..4).map { createRunTask(temporaryDirectory, id = "task_1.${it}_theorem_11") }
        val runDirectory = temporaryDirectory.resolve("run")
        val store = FileRunStore(runDirectory)
        runner(tasks, ScriptedPromptExecutor(List(12) { "proof" }), store,
            specification = runTestSpecification(samplesPerTask = 3),
            assessSubmission = { task, _ ->
                if (task == tasks.first()) EvaluationResult.InfrastructureFailure("evaluation unavailable")
                else EvaluationResult.Accepted
            }).run()

        val run = Json.parseToJsonElement(Files.readString(runDirectory.resolve("run.json"))).jsonObject
        assertEquals("complete", run.getValue("status").jsonPrimitive.content)
        assertEquals("4", run.getValue("max_concurrent_tasks").jsonPrimitive.content)
        for (task in tasks) {
            for (sample in 1..3) {
                val directory = runDirectory.resolve("tasks/${task.manifest.taskId}/samples/${sample.toString().padStart(3, '0')}")
                val record = Json.parseToJsonElement(Files.readString(directory.resolve("record.json"))).jsonObject
                assertEquals(if (task == tasks.first()) "infrastructure_failure" else "accepted",
                    record.getValue("final_result").jsonObject.getValue("kind").jsonPrimitive.content)
                assertTrue(Files.isRegularFile(directory.resolve("trajectory.json")))
            }
        }
    }

    @Test
    fun cancellationStopsActiveTasksAndMarksTheRunFailed() = runBlocking {
        val events = ConcurrentLinkedQueue<RunEvent>()
        val tasks = (1..3).map { createRunTask(temporaryDirectory, id = "task_1.${it}_theorem_11") }
        val started = AtomicInteger()
        val stopped = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val executor = ScriptedPromptExecutor(List(3) { "unused" }) {
            if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.incrementAndGet()
            }
        }
        val store = RecordingRunStore()
        val runner = runner(tasks, executor, store,
            specification = runTestSpecification(maxConcurrentTasks = 2), onEvent = { events.add(it) })

        withTimeout(10_000.milliseconds) {
            val running = async { runner.run() }
            bothStarted.await()
            running.cancelAndJoin()
        }

        assertEquals(2, started.get())
        assertEquals(2, stopped.get())
        assertTrue(store.records.isEmpty())
        assertEquals(listOf("begin", "fail"), store.events)
        assertTrue(!store.failureReason.isNullOrBlank())
        assertEquals(2, events.filterIsInstance<RunEvent.SampleStarted>().size)
        assertTrue(events.none { it is RunEvent.SampleFinished })
    }

    @Test
    fun propagatesCancellationThrownByAnExecutor() = runBlocking {
        val task = createRunTask(temporaryDirectory)
        val executor = ScriptedPromptExecutor(listOf("cancel")) { throw CancellationException("cancelled") }
        val store = RecordingRunStore()

        assertFailsWith<CancellationException> { runner(listOf(task), executor, store).run() }

        assertEquals(listOf("begin", "fail"), store.events)
        assertEquals("cancelled", store.failureReason)
    }

    @Test
    fun marksRunFailedWhenSavingThrows() {
        runBlocking {
            val events = mutableListOf<RunEvent>()
            val task = createRunTask(temporaryDirectory)
            val store = RecordingRunStore(saveFailure = IllegalStateException("disk unavailable"))

            val failure = assertFailsWith<IllegalStateException> {
                runner(listOf(task), ScriptedPromptExecutor(listOf("proof")), store,
                    onEvent = { events += it }).run()
            }

            assertEquals("disk unavailable", failure.message)
            assertEquals(listOf("begin", "save:${task.manifest.taskId}:1", "fail"), store.events)
            assertEquals("disk unavailable", store.failureReason)
            assertTrue(events.none { it is RunEvent.SampleFinished })
        }
    }

    @Test
    fun phaseDeliveryFailuresFailTheRunWithoutPersistingASampleOutcome() = runBlocking {
        val task = createRunTask(temporaryDirectory)
        for (phase in SamplePhase.entries) {
            val failure = IllegalStateException("event delivery failed in $phase")
            val store = RecordingRunStore()
            val thrown = assertFailsWith<IllegalStateException> {
                runner(listOf(task), ScriptedPromptExecutor(listOf("bad", "good")), store,
                    assessSubmission = { _, _ -> EvaluationResult.Rejected("retry") },
                    onEvent = { event ->
                        if (event is RunEvent.PhaseChanged && event.phase == phase) throw failure
                    }).run()
            }
            // Coroutine stack recovery may copy exceptions crossing worker boundaries.
            assertEquals(failure.message, thrown.message)
            assertTrue(store.records.isEmpty())
            assertEquals(listOf("begin", "fail"), store.events)
            assertEquals(failure.message, store.failureReason)
        }
    }

    @Test
    fun reportsInfrastructureFailuresAsFinishedSamplesAfterSaving() = runBlocking {
        val task = createRunTask(temporaryDirectory)
        val store = RecordingRunStore()
        val events = mutableListOf<RunEvent>()
        runner(listOf(task), ScriptedPromptExecutor(emptyList()), store, onEvent = { events += it }).run()

        val finished = assertIs<RunEvent.SampleFinished>(events.last())
        assertIs<EvaluationResult.InfrastructureFailure>(finished.result)
        assertEquals(store.records.single().finalResult, finished.result)
        assertEquals("complete", store.events.last())
    }

    @Test
    fun eventProcessorCollectsConcurrentRunnerUpdatesThroughCompletion() = runBlocking {
        val tasks = (1..4).map { createRunTask(temporaryDirectory, id = "task_1.${it}_theorem_11") }
        val processor = RunEventProcessor(this, capacity = 1)
        val store = RecordingRunStore()
        try {
            runner(tasks, ScriptedPromptExecutor(List(8) { "proof" }), store,
                specification = runTestSpecification(samplesPerTask = 2),
                onEvent = processor::emit).run()
        } finally {
            processor.closeAndJoin()
        }

        assertEquals(8, processor.state.value.acceptedSamples)
        assertTrue(processor.state.value.activeSamples.isEmpty())
        assertEquals(store.records.size, processor.state.value.completedSamples)
        assertEquals("complete", store.events.last())
    }

    @Test
    fun eventProcessorDrainsUpdatesWhenTheRunnerFails() = runBlocking {
        val task = createRunTask(temporaryDirectory)
        val processor = RunEventProcessor(this)
        val store = RecordingRunStore(saveFailure = IllegalStateException("disk unavailable"))
        val failure = assertFailsWith<IllegalStateException> {
            try {
                runner(listOf(task), ScriptedPromptExecutor(listOf("proof")), store,
                    onEvent = processor::emit).run()
            } finally {
                processor.closeAndJoin()
            }
        }

        assertEquals("disk unavailable", failure.message)
        assertEquals(0, processor.state.value.completedSamples)
        assertEquals(RunEvent.PhaseChanged(task.manifest.taskId, 1, SamplePhase.Evaluating),
            processor.state.value.latestEvent)
        assertEquals("fail", store.events.last())
    }

    @Test
    fun consoleShowsPersistedConcurrentResultsBeforeReturning() = runBlocking {
        val tasks = (1..4).map { createRunTask(temporaryDirectory, id = "task_1.${it}_theorem_11") }
        for (mode in listOf(RunConsoleMode.Live, RunConsoleMode.Plain)) {
            val recorder = TerminalRecorder(width = 120)
            val store = RecordingRunStore()
            val console = RunConsole(Terminal(terminalInterface = recorder), RunDashboardContext(
                "test-run", "test-model", temporaryDirectory,
                tasks.associate { it.manifest.taskId to it.manifest.submission.kind }, 2,
            ), mode = mode)
            withTimeout(10_000.milliseconds) {
                console.run { onEvent ->
                    onEvent(RunEvent.RunPhaseChanged(RunPhase.Running))
                    runner(tasks, ScriptedPromptExecutor(List(8) { "proof" }), store,
                        specification = runTestSpecification(samplesPerTask = 2), onEvent = onEvent).run()
                }
            }
            assertEquals(8, store.records.size)
            assertContains(recorder.output(), "8 success")
            assertContains(recorder.output(), "4/4 tasks")
            assertContains(recorder.output(), "8/8 samples")
            assertContains(recorder.output(), "COMPLETE")
        }
    }

    @Test
    fun persistenceFailureCancelsActiveTasksWithoutStartingQueuedTasks() = runBlocking {
        val tasks = (1..3).map { createRunTask(temporaryDirectory, id = "task_1.${it}_theorem_11") }
        val siblingStarted = CompletableDeferred<Unit>()
        val siblingStopped = CompletableDeferred<Unit>()
        val executor = ScriptedPromptExecutor(listOf("finish", "wait", "unused")) {
            if (it == "finish") {
                siblingStarted.await()
                Message.Assistant("proof", ResponseMetaInfo.Empty)
            } else {
                siblingStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    siblingStopped.complete(Unit)
                }
            }
        }
        val store = RecordingRunStore(saveFailure = IllegalStateException("disk unavailable"))

        val failure = withTimeout(10_000.milliseconds) {
            assertFailsWith<IllegalStateException> {
                runner(tasks, executor, store,
                    specification = runTestSpecification(maxConcurrentTasks = 2)).run()
            }
        }

        assertEquals("disk unavailable", failure.message)
        assertTrue(siblingStopped.isCompleted)
        assertEquals(2, executor.prompts.size)
        assertTrue(store.records.isEmpty())
        assertEquals("fail", store.events.last())
        assertEquals("disk unavailable", store.failureReason)
    }

    @Test
    fun rejectsSelectionWithoutFrozenTasks() {
        runBlocking {
            val candidate = createRunTask(
                temporaryDirectory,
                status = TaskStatus.CANDIDATE_PENDING_INDEPENDENT_REVIEW,
            )
            val executor = ScriptedPromptExecutor(listOf("unused"))
            val store = RecordingRunStore()

            assertFailsWith<IllegalStateException> {
                runner(listOf(candidate), executor, store).run()
            }

            assertTrue(store.events.isEmpty())
            assertTrue(executor.prompts.isEmpty())
        }
    }

    private fun runner(
        tasks: List<BenchmarkTask>,
        executor: ScriptedPromptExecutor,
        store: RunStore,
        specification: BenchmarkRunSpecification = runTestSpecification(),
        assessSubmission: (BenchmarkTask, String) -> EvaluationResult = { _, _ ->
            EvaluationResult.Accepted
        },
        onEvent: suspend (RunEvent) -> Unit = {},
    ): BenchmarkRunner {
        val repository = object : TaskRepository {
            override fun findAll(): List<BenchmarkTask> = tasks

            override fun findById(taskId: TaskId): BenchmarkTask? =
                tasks.singleOrNull { it.manifest.taskId == taskId }
        }

        return BenchmarkRunner(
            repository = repository,
            selector = TaskSelector(setOf(EvaluationKind("test_evaluation"))),
            engine = createRunEngine(
                root = temporaryDirectory,
                executor = executor,
                specification = specification,
                assessSubmission = assessSubmission,
            ),
            store = store,
            onEvent = onEvent,
        )
    }

    private class RecordingRunStore(
        private val saveFailure: Exception? = null,
    ) : RunStore {
        val events = mutableListOf<String>()
        val records = mutableListOf<TaskRunRecord>()
        var failureReason: String? = null
            private set
        var taskIds: List<TaskId> = emptyList()
            private set

        override suspend fun begin(
            specification: BenchmarkRunSpecification,
            taskIds: List<TaskId>,
        ) {
            events += "begin"
            this.taskIds = taskIds
        }

        override suspend fun save(record: TaskRunRecord) {
            events += "save:${record.taskId}:${record.sampleIndex}"
            saveFailure?.let { throw it }
            records += record
        }

        override suspend fun complete() {
            events += "complete"
        }

        override suspend fun fail(reason: String) {
            events += "fail"
            failureReason = reason
        }
    }
}
