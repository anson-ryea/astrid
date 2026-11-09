package workspace

import domain.BenchmarkTask

/** Validates and snapshots backend inputs before any model calls. */
fun interface TaskWorkspaceFactory {
    fun prepare(task: BenchmarkTask): TaskWorkspacePlan
}
