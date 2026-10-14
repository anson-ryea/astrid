package run

import dataset.TaskPackageVerifier
import dataset.TaskRepository
import domain.EvaluationResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Runs selected tasks sequentially and saves each sample before continuing. */
class BenchmarkRunner(
    private val repository: TaskRepository,
    private val selector: TaskSelector,
    private val engine: TaskRunEngine,
    private val store: RunStore,
    private val packageVerifier: TaskPackageVerifier = TaskPackageVerifier(),
) {
    suspend fun run() {
        val tasks = selector.select(repository.findAll())
        check(tasks.isNotEmpty()) {
            "no frozen tasks match the selected evaluation kinds"
        }

        // Fail before any model calls if a selected package is invalid.
        tasks.forEach(packageVerifier::verify)

        store.begin(
            specification = engine.specification,
            taskIds = tasks.map { it.manifest.taskId },
        )

        try {
            for (task in tasks) {
                for (sampleIndex in 1..engine.specification.samplesPerTask) {
                    val record = engine.run(task, sampleIndex, store::save)

                    when (val result = record.finalResult) {
                        is EvaluationResult.InfrastructureFailure ->
                            error("run stopped after infrastructure failure in ${record.taskId} " +
                                    "sample $sampleIndex: ${result.reason}")

                        is EvaluationResult.NeedsReview ->
                            error("run stopped because evaluation requires review in " +
                                    "${record.taskId} sample $sampleIndex: ${result.reason}")

                        else -> Unit
                    }
                }
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
