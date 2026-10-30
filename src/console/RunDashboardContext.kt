package console

import domain.SubmissionKind
import domain.TaskId
import java.nio.file.Path

/** Fixed run information needed to label the dashboard, independent of execution events. */
data class RunDashboardContext(
    val runId: String,
    val modelId: String,
    val datasetRoot: Path,
    val taskSubmissions: Map<TaskId, SubmissionKind>,
    val samplesPerTask: Int,
) {
    init {
        require(taskSubmissions.isNotEmpty()) { "dashboard requires at least one task" }
        require(samplesPerTask > 0) { "samples per task must be positive" }
    }

    val datasetPath: Path = datasetRoot.toAbsolutePath().normalize()
    val datasetName: String = datasetPath.fileName?.toString() ?: datasetPath.toString()
    val totalTasks: Int get() = taskSubmissions.size
    val totalSamples: Long get() = totalTasks.toLong() * samplesPerTask

    fun completedTasks(state: RunDashboardState): Int = taskSubmissions.keys.count {
        state.completedSamplesByTask.getOrDefault(it, 0) >= samplesPerTask
    }
}
