package dataset

import domain.ArtefactRole
import domain.BenchmarkTask
import kotlin.io.path.readText

/** Reads declared task artefacts by role. Call it after package verification. */
internal class TaskMaterialReader(private val task: BenchmarkTask) {
    fun readRequired(role: ArtefactRole): String {
        val matches = task.manifest.artefacts.filter { artefact ->
            artefact.role == role
        }
        require(matches.size == 1) {
            "task ${task.manifest.taskId} must declare exactly one $role artefact"
        }
        return task.pathFor(matches.single()).readText().trimEnd()
    }

    fun readAtMostOneOf(roles: Set<ArtefactRole>): Pair<ArtefactRole, String>? {
        val matches = task.manifest.artefacts.filter { artefact ->
            artefact.role in roles
        }
        require(matches.size <= 1) {
            "task ${task.manifest.taskId} declares multiple artefacts for ${roles.joinToString()}"
        }
        return matches.singleOrNull()?.let { artefact ->
            artefact.role to task.pathFor(artefact).readText().trimEnd()
        }
    }
}
