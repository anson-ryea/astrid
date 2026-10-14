package dataset

import domain.BenchmarkTask

/** Validates every task exposed by a repository as one non-empty dataset. */
class DatasetValidator(
    private val taskRepository: TaskRepository,
    private val taskPackageVerifier: TaskPackageVerifier,
) {
    /** Returns all tasks after every package has passed verification. */
    fun validateAll(): List<BenchmarkTask> {
        val tasks = taskRepository.findAll()

        check(tasks.isNotEmpty()) {
            "dataset contains no tasks"
        }

        tasks.forEach { task ->
            taskPackageVerifier.verify(task)
        }

        return tasks
    }
}
