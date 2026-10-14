package backend.lean.protocol

import dataset.TaskMaterialReader
import domain.ArtefactRole
import domain.BenchmarkTask
import protocol.evidenceSection

internal fun leanPromptValues(
    task: BenchmarkTask,
    reader: TaskMaterialReader,
    marker: String,
    allowedEvidenceRoles: Set<ArtefactRole>,
    evidenceRequired: Boolean,
): Map<String, String> {
    val context = reader.readRequired(leanContextRole)
    val target = reader.readRequired(leanTargetRole)
    require(target.split(marker).size == 2) {
        "task ${task.manifest.taskId} target must contain exactly one $marker marker"
    }

    return mapOf(
        "LEAN_CONTEXT" to context,
        "LEAN_TARGET" to target,
        "EVIDENCE_SECTION" to evidenceSection(
            task = task,
            reader = reader,
            allowedRoles = allowedEvidenceRoles,
            required = evidenceRequired,
        ),
    )
}

private val leanContextRole = ArtefactRole("lean_context")
private val leanTargetRole = ArtefactRole("lean_target")
