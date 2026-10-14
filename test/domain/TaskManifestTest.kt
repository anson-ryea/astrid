package domain

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class TaskManifestTest {
    @Test
    fun acceptsManifestWithDistinctArtefactPaths() {
        val artefacts = listOf(
            artefact("lean_context", "Context.lean"),
            artefact("lean_context", "Supporting.lean"),
            artefact("lean_target", "Target.lean"),
        )

        val manifest = manifest(artefacts = artefacts)

        assertEquals(artefacts, manifest.artefacts)
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        for (version in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> {
                manifest(schemaVersion = version)
            }
        }
    }

    @Test
    fun rejectsEmptyArtefactList() {
        assertFailsWith<IllegalArgumentException> {
            manifest(artefacts = emptyList())
        }
    }

    @Test
    fun rejectsDuplicateArtefactPaths() {
        val artefacts = listOf(
            artefact("lean_context", "Context.lean"),
            artefact("paper_evidence", "Context.lean"),
        )

        assertFailsWith<IllegalArgumentException> {
            manifest(artefacts = artefacts)
        }
    }

    private fun manifest(
        schemaVersion: Int = TaskManifest.SCHEMA_VERSION,
        artefacts: List<TaskArtefact> = listOf(
            artefact("lean_context", "Context.lean"),
        ),
    ): TaskManifest {
        return TaskManifest(
            schemaVersion = schemaVersion,
            taskId = TaskId("task_1.1_theorem_11"),
            paperId = PaperId("paper_1"),
            sourceResultId = SourceResultId("theorem_11"),
            taskType = TaskTypeId(2),
            protocol = ProtocolId("lean_proof"),
            status = TaskStatus.CANDIDATE_PENDING_INDEPENDENT_REVIEW,
            condition = ConditionId("paper_evidence"),
            contextId = ContextId("theorem_11"),
            artefacts = artefacts,
            submission = SubmissionSpecification(
                kind = SubmissionKind("lean_proof_body"),
                allowLocalHelpers = true,
                allowTopLevelDeclarations = false,
            ),
            evaluation = EvaluationSpecification(
                kind = EvaluationKind("lean_kernel"),
                timeout = 180.seconds,
                allowedAxioms = setOf(
                    "propext",
                    "Classical.choice",
                    "Quot.sound",
                ),
                forbiddenMechanisms = setOf("sorry"),
                forbiddenIdentifiers = emptySet(),
            ),
        )
    }

    private fun artefact(
        role: String,
        path: String,
    ): TaskArtefact {
        return TaskArtefact(
            role = ArtefactRole(role),
            path = ArtefactPath(Path(path)),
            sha256Digest = Sha256Digest("0".repeat(64)),
        )
    }
}
