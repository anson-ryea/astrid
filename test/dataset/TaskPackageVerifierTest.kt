package dataset

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
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class TaskPackageVerifierTest {
    private val verifier = TaskPackageVerifier()

    private val contextArtefact = TaskArtefact(
        role = ArtefactRole("lean_context"),
        path = ArtefactPath(Path.of("Context.lean")),
        sha256Digest = Sha256Digest("0".repeat(64)),
    )

    @Test
    fun acceptsCompleteTaskPackage() {
        withTaskPackage { _, task ->
            verifier.verify(task)
        }
    }

    @Test
    fun rejectsChangedArtefactContents() {
        withTaskPackage { packageDirectory, task ->
            Files.writeString(
                packageDirectory.resolve("Context.lean"),
                "changed task artefact",
            )

            val failure = assertFailsWith<IllegalStateException> {
                verifier.verify(task)
            }

            assertContains(
                failure.message.orEmpty(),
                "task artefact hash mismatch",
            )
        }
    }

    @Test
    fun rejectsMissingPackageDirectory() {
        withTaskPackage { packageDirectory, task ->
            deleteTree(packageDirectory)

            val failure = assertFailsWith<IllegalStateException> {
                verifier.verify(task)
            }

            assertContains(
                failure.message.orEmpty(),
                "task package is not a directory",
            )
        }
    }

    @Test
    fun rejectsMissingManifest() {
        withTaskPackage { packageDirectory, task ->
            Files.delete(
                packageDirectory.resolve("manifest.json"),
            )

            val failure = assertFailsWith<IllegalStateException> {
                verifier.verify(task)
            }

            assertContains(
                failure.message.orEmpty(),
                "task package is missing manifest.json",
            )
        }
    }

    @Test
    fun rejectsMissingDeclaredArtefact() {
        withTaskPackage { packageDirectory, task ->
            Files.delete(
                packageDirectory.resolve("Context.lean"),
            )

            val failure = assertFailsWith<IllegalStateException> {
                verifier.verify(task)
            }

            assertContains(
                failure.message.orEmpty(),
                "task artefact is missing or is not a regular file",
            )
        }
    }

    @Test
    fun rejectsArtefactDirectory() {
        withTaskPackage { packageDirectory, task ->
            val artefactPath =
                packageDirectory.resolve("Context.lean")

            Files.delete(artefactPath)
            Files.createDirectory(artefactPath)

            val failure = assertFailsWith<IllegalStateException> {
                verifier.verify(task)
            }

            assertContains(
                failure.message.orEmpty(),
                "task artefact is missing or is not a regular file",
            )
        }
    }

    @Test
    fun rejectsSymbolicLinkArtefact() {
        withTaskPackage { packageDirectory, task ->
            val artefactPath =
                packageDirectory.resolve("Context.lean")
            val outsideFile =
                packageDirectory.parent.resolve(
                    "outside-context.lean",
                )

            Files.writeString(
                outsideFile,
                "outside package",
            )
            Files.delete(artefactPath)
            Files.createSymbolicLink(
                artefactPath,
                outsideFile,
            )

            val failure = assertFailsWith<IllegalStateException> {
                verifier.verify(task)
            }

            assertContains(
                failure.message.orEmpty(),
                "task artefact must not be a symbolic link",
            )
        }
    }

    @Test
    fun rejectsArtefactEscapingThroughSymbolicLinkDirectory() {
        val nestedArtefact = TaskArtefact(
            role = ArtefactRole("lean_context"),
            path = ArtefactPath(
                Path.of("inputs", "Context.lean"),
            ),
            sha256Digest = Sha256Digest("0".repeat(64)),
        )

        withTaskPackage(nestedArtefact) { packageDirectory, task ->
            val inputsDirectory =
                packageDirectory.resolve("inputs")
            val outsideDirectory =
                packageDirectory.parent.resolve("outside")

            Files.createDirectories(outsideDirectory)
            Files.writeString(
                outsideDirectory.resolve("Context.lean"),
                "outside package",
            )

            Files.delete(
                inputsDirectory.resolve("Context.lean"),
            )
            Files.delete(inputsDirectory)
            Files.createSymbolicLink(
                inputsDirectory,
                outsideDirectory,
            )

            val failure = assertFailsWith<IllegalStateException> {
                verifier.verify(task)
            }

            assertContains(
                failure.message.orEmpty(),
                "task artefact escapes its package directory",
            )
        }
    }

    private fun withTaskPackage(
        artefact: TaskArtefact = contextArtefact,
        block: (
            packageDirectory: Path,
            task: BenchmarkTask,
        ) -> Unit,
    ) {
        val temporaryDirectory =
            Files.createTempDirectory("task-package-verifier-")
        val packageDirectory = temporaryDirectory
            .resolve("task_1.1_theorem_1")
            .toAbsolutePath()
            .normalize()

        try {
            Files.createDirectories(packageDirectory)
            Files.writeString(
                packageDirectory.resolve("manifest.json"),
                "{}",
            )

            val artefactPath =
                packageDirectory.resolve(artefact.path.value)

            Files.createDirectories(artefactPath.parent)
            Files.writeString(
                artefactPath,
                "task artefact",
            )

            val declaredArtefact = artefact.copy(
                sha256Digest = HashCalculator().sha256(artefactPath),
            )
            val task = BenchmarkTask(
                manifest = manifest(declaredArtefact),
                packageDirectory = packageDirectory,
            )

            block(packageDirectory, task)
        } finally {
            deleteTree(temporaryDirectory)
        }
    }

    private fun manifest(
        artefact: TaskArtefact,
    ): TaskManifest {
        return TaskManifest(
            schemaVersion = TaskManifest.SCHEMA_VERSION,
            taskId = TaskId("task_1.1_theorem_1"),
            paperId = PaperId("paper_1"),
            sourceResultId = SourceResultId("theorem_1"),
            taskType = TaskTypeId(3),
            protocol = ProtocolId("lean_proof"),
            status =
                TaskStatus.CANDIDATE_PENDING_INDEPENDENT_REVIEW,
            condition = ConditionId("statement_only"),
            contextId = ContextId("theorem_11"),
            artefacts = listOf(artefact),
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
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths ->
            paths
                .sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
    }
}
