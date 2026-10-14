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
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class TaskProtocolRegistryTest {
    @Test
    fun findsProtocolDeclaredByTask() {
        val proof = StubProtocol(ProtocolId("lean_proof"))
        val statement = StubProtocol(ProtocolId("lean_statement"))
        val registry = TaskProtocolRegistry(listOf(statement, proof))

        assertSame(proof, registry.findFor(task(ProtocolId("lean_proof"))))
    }

    @Test
    fun rejectsDuplicateProtocolIdentifiers() {
        val failure = assertFailsWith<IllegalArgumentException> {
            TaskProtocolRegistry(
                listOf(
                    StubProtocol(ProtocolId("lean_proof")),
                    StubProtocol(ProtocolId("lean_proof")),
                ),
            )
        }

        assertContains(failure.message.orEmpty(), "duplicate task protocols: lean_proof")
    }

    @Test
    fun reportsUnsupportedTaskProtocol() {
        val registry = TaskProtocolRegistry(emptyList())

        val failure = assertFailsWith<IllegalArgumentException> {
            registry.findFor(task(ProtocolId("lean_proof")))
        }

        assertContains(failure.message.orEmpty(), "unsupported task protocol: lean_proof")
    }

    private fun task(protocolId: ProtocolId): BenchmarkTask {
        val manifest = TaskManifest(
            schemaVersion = TaskManifest.SCHEMA_VERSION,
            taskId = TaskId("task_1.1_theorem_11"),
            paperId = PaperId("paper_1"),
            sourceResultId = SourceResultId("theorem_11"),
            taskType = TaskTypeId(2),
            protocol = protocolId,
            status = TaskStatus.CANDIDATE_PENDING_INDEPENDENT_REVIEW,
            condition = ConditionId("paper_evidence"),
            contextId = ContextId("theorem_11"),
            artefacts = listOf(
                TaskArtefact(
                    role = ArtefactRole("lean_context"),
                    path = ArtefactPath(Path("Context.lean")),
                    sha256Digest = Sha256Digest("0".repeat(64)),
                ),
            ),
            submission = SubmissionSpecification(
                kind = SubmissionKind("lean_proof_body"),
                allowLocalHelpers = true,
                allowTopLevelDeclarations = false,
            ),
            evaluation = EvaluationSpecification(
                kind = EvaluationKind("lean_kernel"),
                timeout = 180.seconds,
                allowedAxioms = emptySet(),
                forbiddenMechanisms = emptySet(),
                forbiddenIdentifiers = emptySet(),
            ),
        )

        return BenchmarkTask(
            manifest = manifest,
            packageDirectory = Path("build", "test-tasks", manifest.taskId.value)
                .toAbsolutePath()
                .normalize(),
        )
    }

    private class StubProtocol(override val id: ProtocolId) : TaskProtocol {
        override fun renderPrompt(task: BenchmarkTask, submissionInstructions: String?): String = ""

        override fun extractSubmission(response: String): String = response
    }
}
