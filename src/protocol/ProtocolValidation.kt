package protocol

import domain.BenchmarkTask
import domain.ProtocolId
import domain.SubmissionKind

internal fun requireTaskShape(
    task: BenchmarkTask,
    protocolId: ProtocolId,
    submissionKind: SubmissionKind,
) {
    require(task.manifest.protocol == protocolId) {
        "task ${task.manifest.taskId} does not use protocol $protocolId"
    }
    require(task.manifest.submission.kind == submissionKind) {
        "task ${task.manifest.taskId} does not require $submissionKind"
    }
}
