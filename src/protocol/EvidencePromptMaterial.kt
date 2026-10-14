package protocol

import dataset.TaskMaterialReader
import domain.ArtefactRole
import domain.BenchmarkTask

internal fun evidenceSection(
    task: BenchmarkTask,
    reader: TaskMaterialReader,
    allowedRoles: Set<ArtefactRole>,
    required: Boolean,
): String {
    val evidence = reader.readAtMostOneOf(allEvidenceRoles)
    require(evidence == null || evidence.first in allowedRoles) {
        "task ${task.manifest.taskId} declares unsupported evidence for ${task.manifest.protocol}"
    }
    require(!required || evidence != null) {
        "task ${task.manifest.taskId} is missing paper evidence"
    }

    return evidence?.let { (_, contents) ->
        "## Evidence\n\n$contents\n"
    }.orEmpty()
}

internal val paperEvidenceRole = ArtefactRole("paper_evidence")
internal val enrichedEvidenceRole = ArtefactRole("enriched_evidence")
private val allEvidenceRoles = setOf(paperEvidenceRole, enrichedEvidenceRole)
