package console

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import run.RunEvent

/**
 * Collects concurrent execution events through a bounded queue and reduces them in one coroutine.
 * The caller owns [scope] and must call [closeAndJoin] after producers finish to drain pending events.
 * Cancelling the scope stops processing and releases suspended producers, discarding pending events.
 */
class RunEventProcessor(
    scope: CoroutineScope,
    capacity: Int = 64,
) {
    init {
        require(capacity in 1 until Channel.UNLIMITED) { "event queue capacity must be positive and bounded" }
    }

    private val events = Channel<RunEvent>(capacity)
    private val currentState = MutableStateFlow(RunDashboardState())

    /** Snapshots may be conflated; every queued event is still reduced before normal shutdown. */
    val state: StateFlow<RunDashboardState> = currentState.asStateFlow()

    private val consumer = scope.launch {
        for (event in events) {
            currentState.value = currentState.value.reduce(event)
        }
    }.also { job ->
        // Also runs if the scope is cancelled before the consumer starts.
        job.invokeOnCompletion { failure ->
            if (failure != null) events.cancel()
        }
    }

    /** Safe for concurrent producers; suspends when the queue is full and fails after shutdown. */
    suspend fun emit(event: RunEvent) {
        events.send(event)
    }

    /** Stops accepting new events and waits for queued events to be processed while the scope is active. */
    suspend fun closeAndJoin() {
        events.close()
        consumer.join()
    }
}
