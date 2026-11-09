package console

import domain.TaskId

/** Identifies one independent sample even when several tasks are running concurrently. */
data class SampleKey(val taskId: TaskId, val sampleIndex: Int)
