package console

import domain.SubmissionKind
import domain.TaskId
import java.nio.file.Path
import run.BenchmarkRunConfig

data class RunTaskProblem(val paper: String, val problem: String)

/** Fixed run information for the dashboard and info page, independent of execution events. */
data class RunDashboardContext(
    val runId: String,
    val modelId: String,
    val datasetRoot: Path,
    val taskSubmissions: Map<TaskId, SubmissionKind>,
    val samplesPerTask: Int,
    val configPath: Path? = null,
    val outputPath: Path? = null,
    val runConfig: BenchmarkRunConfig? = null,
    val backendId: String? = null,
    val taskProblems: Map<TaskId, RunTaskProblem> = emptyMap(),
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
