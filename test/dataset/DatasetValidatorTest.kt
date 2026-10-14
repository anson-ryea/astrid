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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class DatasetValidatorTest {
    @Test
    fun returnsAllValidatedTasks() {
        withTemporaryDirectory { root ->
            val first = createTask(
                root,
                "task_1.1_theorem_1",
            )
            val second = createTask(
                root,
                "task_1.2_theorem_2",
            )
            val tasks = listOf(first, second)
            val validator = validator(tasks)

            assertEquals(
                tasks,
                validator.validateAll(),
            )
        }
    }

    @Test
    fun rejectsEmptyDataset() {
        val validator = validator(emptyList())

        val failure = assertFailsWith<IllegalStateException> {
            validator.validateAll()
        }

        assertContains(
            failure.message.orEmpty(),
            "dataset contains no tasks",
        )
    }

    @Test
    fun rejectsInvalidTaskPackage() {
        withTemporaryDirectory { root ->
            val task = createTask(
                root,
                "task_1.1_theorem_1",
            )
            Files.delete(
                task.packageDirectory.resolve("Context.lean"),
            )
            val validator = validator(listOf(task))

            val failure = assertFailsWith<IllegalStateException> {
                validator.validateAll()
            }

            assertContains(
                failure.message.orEmpty(),
                "task artefact is missing or is not a regular file",
            )
        }
    }

    private fun validator(
        tasks: List<BenchmarkTask>,
    ): DatasetValidator {
        return DatasetValidator(
            taskRepository = FixedTaskRepository(tasks),
            taskPackageVerifier = TaskPackageVerifier(),
        )
    }

    private fun createTask(
        root: Path,
        taskId: String,
    ): BenchmarkTask {
        val packageDirectory = root
            .resolve(taskId)
            .toAbsolutePath()
            .normalize()

        Files.createDirectories(packageDirectory)
        Files.writeString(
            packageDirectory.resolve("manifest.json"),
            "{}",
        )
        val contextPath = packageDirectory.resolve("Context.lean")
        Files.writeString(
            contextPath,
            "task context",
        )

        return BenchmarkTask(
            manifest = manifest(
                taskId = taskId,
                contextDigest = HashCalculator().sha256(contextPath),
            ),
            packageDirectory = packageDirectory,
        )
    }

    private fun manifest(
        taskId: String,
        contextDigest: Sha256Digest,
    ): TaskManifest {
        return TaskManifest(
            schemaVersion = TaskManifest.SCHEMA_VERSION,
            taskId = TaskId(taskId),
            paperId = PaperId("paper_1"),
            sourceResultId = SourceResultId(
                taskId.substringAfter('_').substringAfter('_'),
            ),
            taskType = TaskTypeId(3),
            protocol = ProtocolId("lean_proof"),
            status = TaskStatus.CANDIDATE_PENDING_INDEPENDENT_REVIEW,
            condition = ConditionId("statement_only"),
            contextId = ContextId("theorem_11"),
            artefacts = listOf(
                TaskArtefact(
                    role = ArtefactRole("lean_context"),
                    path = ArtefactPath(Path.of("Context.lean")),
                    sha256Digest = contextDigest,
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
    }

    private fun withTemporaryDirectory(
        block: (Path) -> Unit,
    ) {
        val root = Files.createTempDirectory(
            "dataset-validator-",
        )

        try {
            block(root)
        } finally {
            deleteTree(root)
        }
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths ->
            paths
                .sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
    }

    private class FixedTaskRepository(
        private val tasks: List<BenchmarkTask>,
    ) : TaskRepository {
        override fun findAll(): List<BenchmarkTask> {
            return tasks
        }

        override fun findById(
            taskId: TaskId,
        ): BenchmarkTask? {
            return tasks.singleOrNull { task ->
                task.manifest.taskId == taskId
            }
        }
    }
}
