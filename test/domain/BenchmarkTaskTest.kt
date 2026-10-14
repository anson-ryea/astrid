package domain

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class BenchmarkTaskTest {
    private val declaredArtefact = artefact(
        role = "lean_context",
        path = "Context.lean",
    )

    private val manifest = TaskManifest(
        schemaVersion = TaskManifest.SCHEMA_VERSION,
        taskId = TaskId("task_1.1_theorem_11"),
        paperId = PaperId("paper_1"),
        sourceResultId = SourceResultId("theorem_11"),
        taskType = TaskTypeId(2),
        protocol = ProtocolId("lean_proof"),
        status = TaskStatus.CANDIDATE_PENDING_INDEPENDENT_REVIEW,
        condition = ConditionId("paper_evidence"),
        contextId = ContextId("theorem_11"),
        artefacts = listOf(declaredArtefact),
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

    private val packageDirectory = Path(
        "build",
        "test-tasks",
        manifest.taskId.value,
    ).toAbsolutePath().normalize()

    @Test
    fun acceptsValidPackageDirectory() {
        val task = BenchmarkTask(
            manifest = manifest,
            packageDirectory = packageDirectory,
        )

        assertEquals(packageDirectory, task.packageDirectory)
    }

    @Test
    fun rejectsRelativePackageDirectory() {
        val relativeDirectory = Path(
            "dataset",
            "paper_1",
            "tasks",
            manifest.taskId.value,
        )

        assertFailsWith<IllegalArgumentException> {
            BenchmarkTask(manifest, relativeDirectory)
        }
    }

    @Test
    fun rejectsNonNormalisedPackageDirectory() {
        val nonNormalisedDirectory = packageDirectory
            .parent
            .resolve("temporary")
            .resolve("..")
            .resolve(manifest.taskId.value)

        assertFailsWith<IllegalArgumentException> {
            BenchmarkTask(manifest, nonNormalisedDirectory)
        }
    }

    @Test
    fun rejectsMismatchedDirectoryName() {
        val mismatchedDirectory = packageDirectory
            .parent
            .resolve("task_1.2_theorem_11")

        assertFailsWith<IllegalArgumentException> {
            BenchmarkTask(manifest, mismatchedDirectory)
        }
    }

    @Test
    fun resolvesDeclaredArtefact() {
        val task = BenchmarkTask(manifest, packageDirectory)

        assertEquals(
            packageDirectory.resolve("Context.lean"),
            task.pathFor(declaredArtefact),
        )
    }

    @Test
    fun rejectsUndeclaredArtefact() {
        val task = BenchmarkTask(manifest, packageDirectory)
        val undeclaredArtefact = artefact(
            role = "lean_target",
            path = "Target.lean",
        )

        assertFailsWith<IllegalArgumentException> {
            task.pathFor(undeclaredArtefact)
        }
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
