package run

import domain.TaskId

/** Persists run metadata and each completed sample. */
interface RunStore {
    suspend fun begin(
        specification: BenchmarkRunSpecification,
        taskIds: List<TaskId>
    )

    suspend fun save(record: TaskRunRecord)

    suspend fun complete()

    /** Ends a begun run without discarding samples already saved. */
    suspend fun fail(reason: String)
}
