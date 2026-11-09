package run

import dataset.TaskRepository
import domain.BenchmarkTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs tasks concurrently, keeping each task's samples sequential and durably saved.
 * [onEvent] is called from concurrent workers; consumers must support concurrent calls.
 * Delivery is suspending and failures propagate as run failures.
 */
class BenchmarkRunner(
    private val repository: TaskRepository,
    private val selector: TaskSelector,
    private val engine: TaskRunEngine,
    private val store: RunStore,
    private val onEvent: suspend (RunEvent) -> Unit = {},
) {
    suspend fun run() {
        val tasks = selector.select(repository.findAll())
        check(tasks.isNotEmpty()) {
            "no frozen tasks match the selected evaluation kinds"
        }

        store.begin(
            specification = engine.specification,
            taskIds = tasks.map { it.manifest.taskId },
        )

        try {
            val saveMutex = Mutex()
            coroutineScope {
                val pendingTasks = Channel<BenchmarkTask>(tasks.size)
                tasks.forEach { pendingTasks.send(it) }
                pendingTasks.close()
                List(minOf(engine.specification.maxConcurrentTasks, tasks.size)) {
                    // Preparation and evaluation also perform blocking file and process I/O.
                    async(Dispatchers.IO) {
                        for (task in pendingTasks) {
                            for (sampleIndex in 1..engine.specification.samplesPerTask) {
                                onEvent(RunEvent.SampleStarted(task.manifest.taskId, sampleIndex))
                                // Every evaluation outcome is a sample result, not a run error.
                                engine.run(task, sampleIndex, onEvent = onEvent) { record ->
                                    saveMutex.withLock { store.save(record) }
                                    onEvent(RunEvent.SampleFinished(record.taskId, record.sampleIndex, record.outcome))
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            store.complete()
        } catch (failure: Exception) {
            val reason = failure.message?.takeIf(String::isNotBlank)
                ?: "run failed: ${failure.javaClass.simpleName}"
            try {
                withContext(NonCancellable) { store.fail(reason) }
            } catch (storeFailure: Exception) {
                failure.addSuppressed(storeFailure)
            }
            throw failure
        }
    }
}
