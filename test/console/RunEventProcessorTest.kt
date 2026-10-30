package console

import domain.EvaluationResult
import domain.TaskId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import run.RunEvent
import run.SamplePhase

class RunEventProcessorTest {
    private val taskId = TaskId("task_1.1_theorem_11")

    @Test
    fun reducesEveryConcurrentEventWithoutRequiringASnapshotSubscriber() = runBlocking {
        withTimeout(10.seconds) {
            val processor = RunEventProcessor(this, capacity = 2)
            (1..4).map { worker ->
                launch(Dispatchers.Default) {
                    val id = TaskId("task_1.${worker}_theorem_11")
                    for (sample in 1..20) {
                        processor.emit(RunEvent.SampleStarted(id, sample))
                        processor.emit(RunEvent.PhaseChanged(id, sample, SamplePhase.Generating))
                        processor.emit(RunEvent.PhaseChanged(id, sample, SamplePhase.Evaluating))
                        val result = when (sample % 4) {
                            0 -> EvaluationResult.Accepted
                            1 -> EvaluationResult.Rejected("invalid submission")
                            2 -> EvaluationResult.NeedsReview("manual review")
                            else -> EvaluationResult.InfrastructureFailure("provider unavailable")
                        }
                        processor.emit(RunEvent.SampleFinished(id, sample, result))
                    }
                }
            }.joinAll()
            processor.closeAndJoin()

            val state = processor.state.value
            assertEquals(80, state.completedSamples)
            assertEquals(20, state.acceptedSamples)
            assertEquals(20, state.rejectedSamples)
            assertEquals(20, state.needsReviewSamples)
            assertEquals(20, state.infrastructureFailureSamples)
            assertTrue(state.activeSamples.isEmpty())
        }
    }

    @Test
    fun suspendsProducersWhenTheQueueIsFull() = runBlocking {
        withTimeout(10.seconds) {
            val processor = RunEventProcessor(this, capacity = 1)
            // The consumer has been scheduled but cannot start until this coroutine yields.
            processor.emit(RunEvent.SampleStarted(taskId, 1))
            val phase = RunEvent.PhaseChanged(taskId, 1, SamplePhase.Generating)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { processor.emit(phase) }
            assertFalse(pending.isCompleted)

            pending.await()
            processor.closeAndJoin()
            assertEquals(phase, processor.state.value.latestEvent)
            assertEquals(SamplePhase.Generating, processor.state.value.activeSamples[SampleKey(taskId, 1)])
        }
    }

    @Test
    fun closingDrainsBufferedEventsAndRejectsNewEvents() = runBlocking<Unit> {
        val processor = RunEventProcessor(this)
        val initial = processor.state.value
        for (sample in 1..8) {
            processor.emit(RunEvent.SampleStarted(taskId, sample))
            processor.emit(RunEvent.SampleFinished(taskId, sample, EvaluationResult.Accepted))
        }
        // All sixteen events fit in the buffer without yielding to the consumer.
        assertEquals(0, processor.state.value.completedSamples)
        processor.closeAndJoin()
        processor.closeAndJoin()

        assertEquals(8, processor.state.value.acceptedSamples)
        assertTrue(processor.state.value.activeSamples.isEmpty())
        assertEquals(0, initial.completedSamples)
        assertFailsWith<ClosedSendChannelException> {
            processor.emit(RunEvent.SampleStarted(taskId, 9))
        }
    }

    @Test
    fun cancellingBeforeTheConsumerStartsReleasesBlockedProducers() = runBlocking<Unit> {
        withTimeout(10.seconds) {
            val consumerScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val processor = RunEventProcessor(consumerScope, capacity = 1)
            try {
                processor.emit(RunEvent.SampleStarted(taskId, 1))
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    processor.emit(RunEvent.PhaseChanged(taskId, 1, SamplePhase.Generating))
                }
                assertFalse(pending.isCompleted)
                consumerScope.cancel()

                assertFailsWith<CancellationException> { pending.await() }
                processor.closeAndJoin()
                assertEquals(0, processor.state.value.completedSamples)
                assertFailsWith<CancellationException> {
                    processor.emit(RunEvent.SampleStarted(taskId, 2))
                }
            } finally {
                consumerScope.cancel()
            }
        }
    }

    @Test
    fun rejectsUnboundedOrInvalidQueueCapacities() = runBlocking {
        for (capacity in listOf(-2, -1, 0, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { RunEventProcessor(this, capacity) }
        }
    }
}
