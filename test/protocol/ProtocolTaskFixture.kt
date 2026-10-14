package protocol

import domain.ArtefactPath
import domain.ArtefactRole
import domain.BenchmarkTask
import domain.ConditionId
import domain.ContextId
import domain.EvaluationKind
import domain.EvaluationSpecification
import domain.PaperId
import domain.ProtocolId
import domain.Sha256Digest
import domain.SourceResultId
import domain.SubmissionKind
import domain.SubmissionSpecification
import domain.TaskArtefact
import domain.TaskId
import domain.TaskManifest
import domain.TaskStatus
import domain.TaskTypeId
import java.nio.file.Files
import java.util.Comparator
import kotlin.time.Duration.Companion.seconds

internal fun withProtocolTask(
    protocol: String,
    submissionKind: String,
    artefacts: Map<String, String>,
    block: (BenchmarkTask) -> Unit,
) {
    val root = Files.createTempDirectory("protocol-task-")
    try {
        val packageDirectory = root.resolve("task_1.1_theorem_11")
        Files.createDirectory(packageDirectory)

        val declaredArtefacts = artefacts.map { (role, contents) ->
            val relativePath = java.nio.file.Path.of("$role.txt")
            Files.writeString(packageDirectory.resolve(relativePath), contents)
            TaskArtefact(
                role = ArtefactRole(role),
                path = ArtefactPath(relativePath),
                sha256Digest = Sha256Digest("0".repeat(64)),
            )
        }
        val manifest = TaskManifest(
            schemaVersion = TaskManifest.SCHEMA_VERSION,
            taskId = TaskId("task_1.1_theorem_11"),
            paperId = PaperId("paper_1"),
            sourceResultId = SourceResultId("theorem_11"),
            taskType = TaskTypeId(1),
            protocol = ProtocolId(protocol),
            status = TaskStatus.CANDIDATE_PENDING_INDEPENDENT_REVIEW,
            condition = ConditionId("paper_evidence"),
            contextId = ContextId("theorem_11"),
            artefacts = declaredArtefacts,
            submission = SubmissionSpecification(
                kind = SubmissionKind(submissionKind),
                allowLocalHelpers = false,
                allowTopLevelDeclarations = false,
            ),
            evaluation = EvaluationSpecification(
                kind = EvaluationKind("lean_elaboration_and_review"),
                timeout = 180.seconds,
                allowedAxioms = emptySet(),
                forbiddenMechanisms = emptySet(),
                forbiddenIdentifiers = emptySet(),
            ),
        )
        block(BenchmarkTask(manifest, packageDirectory))
    } finally {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
