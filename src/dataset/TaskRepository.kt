package dataset

import domain.BenchmarkTask
import domain.TaskId

/** Provides benchmark tasks independently of their storage representation. */
interface TaskRepository {
    /** Returns every task available from this repository. */
    fun findAll(): List<BenchmarkTask>

    /** Returns the task identified by [taskId], or `null` when it is absent. */
    fun findById(taskId: TaskId): BenchmarkTask?
}
