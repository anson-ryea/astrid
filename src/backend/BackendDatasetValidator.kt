package backend

import dataset.TaskPackageVerifier
import domain.BenchmarkTask
import workspace.TaskWorkspaceFactory

/** Checks dataset packages and backend readiness without contacting a model. */
class BackendDatasetValidator(
    private val backend: FormalisationBackend,
    private val workspaceFactory: TaskWorkspaceFactory? = backend.workspaceFactory,
    private val packageVerifier: TaskPackageVerifier = TaskPackageVerifier(),
) {
    fun validateAll(tasks: List<BenchmarkTask>, onValidated: (BenchmarkTask) -> Unit = {}) {
        check(tasks.isNotEmpty()) { "dataset contains no tasks supported by backend ${backend.id}" }
        tasks.forEach { task ->
            packageVerifier.verify(task)
            backend.preflight(task)
            workspaceFactory?.prepare(task)
            onValidated(task)
        }
    }
}
