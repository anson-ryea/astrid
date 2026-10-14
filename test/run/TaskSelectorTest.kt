package run

import domain.*
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
import domain.TaskTypeId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class TaskSelectorTest {
    private val selector = TaskSelector(setOf(EvaluationKind("lean_kernel")))

    @Test
    fun selectsOnlyFrozenTasksWithSupportedEvaluationKind() {
        val frozenProof = task("task_1.1_theorem_1", TaskStatus.FROZEN, "lean_kernel")
        val candidateProof = task(
            "task_1.2_theorem_2",
            TaskStatus.CANDIDATE_PENDING_INDEPENDENT_REVIEW,
            "lean_kernel",
        )
        val reviewTask = task(
            "task_1.3_definition_1",
            TaskStatus.FROZEN,
            "lean_elaboration_and_review",
        )

        assertEquals(listOf(frozenProof), selector.select(
            listOf(reviewTask, candidateProof, frozenProof),
        ))
    }

    @Test
    fun sortsSelectedTasksByIdentifier() {
        val later = task("task_1.2_theorem_2", TaskStatus.FROZEN, "lean_kernel")
        val earlier = task("task_1.1_theorem_1", TaskStatus.FROZEN, "lean_kernel")

        assertEquals(listOf(earlier, later), selector.select(listOf(later, earlier)))
    }

    @Test
    fun rejectsEmptyEvaluationKinds() {
        assertFailsWith<IllegalArgumentException> {
            TaskSelector(emptySet())
        }
    }

    private fun task(
        id: String,
        status: TaskStatus,
        evaluationKind: String,
    ): BenchmarkTask {
        val manifest = TaskManifest(
            schemaVersion = TaskManifest.SCHEMA_VERSION,
            taskId = TaskId(id),
            paperId = PaperId("paper_1"),
            sourceResultId = SourceResultId("theorem_1"),
            taskType = TaskTypeId(2),
            protocol = ProtocolId("lean_proof"),
            status = status,
            condition = ConditionId("statement_only"),
            contextId = ContextId("test_context"),
            artefacts = listOf(
                TaskArtefact(
                    role = ArtefactRole("lean_target"),
                    path = ArtefactPath(Path.of("Target.lean")),
                    sha256Digest = Sha256Digest("0".repeat(64)),
                ),
            ),
            submission = SubmissionSpecification(
                kind = SubmissionKind("lean_proof_body"),
                allowLocalHelpers = false,
                allowTopLevelDeclarations = false,
            ),
            evaluation = EvaluationSpecification(
                kind = EvaluationKind(evaluationKind),
                timeout = 10.seconds,
                allowedAxioms = emptySet(),
                forbiddenMechanisms = emptySet(),
                forbiddenIdentifiers = emptySet(),
            ),
        )
        val directory = Path.of(System.getProperty("java.io.tmpdir"))
            .resolve(id)
            .toAbsolutePath()
            .normalize()

        return BenchmarkTask(manifest, directory)
    }
}
