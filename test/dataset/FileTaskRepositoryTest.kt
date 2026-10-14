package dataset

import domain.ArtefactPath
import domain.ArtefactRole
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
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class FileTaskRepositoryTest {
    @Test
    fun discoversTasksAcrossPaperDirectories() {
        withTemporaryDataset { datasetRoot ->
            createTaskDirectory(
                datasetRoot,
                paperId = "paper_2",
                taskId = "task_2.1_theorem_1",
            )
            createTaskDirectory(
                datasetRoot,
                paperId = "paper_1",
                taskId = "task_1.2_theorem_2",
            )
            createTaskDirectory(
                datasetRoot,
                paperId = "paper_1",
                taskId = "task_1.1_theorem_1",
            )
            Files.createDirectories(datasetRoot.resolve("shared"))

            val repository = repository(
                datasetRoot,
                listOf(
                    manifest("task_2.1_theorem_1", "paper_2"),
                    manifest("task_1.2_theorem_2", "paper_1"),
                    manifest("task_1.1_theorem_1", "paper_1"),
                ),
            )

            assertEquals(
                listOf(
                    "task_1.1_theorem_1",
                    "task_1.2_theorem_2",
                    "task_2.1_theorem_1",
                ),
                repository.findAll().map { task ->
                    task.manifest.taskId.value
                },
            )
        }
    }

    @Test
    fun findsTaskByIdentifier() {
        withTemporaryDataset { datasetRoot ->
            val taskId = TaskId("task_1.1_theorem_1")
            createTaskDirectory(
                datasetRoot,
                paperId = "paper_1",
                taskId = taskId.value,
            )
            val repository = repository(
                datasetRoot,
                listOf(manifest(taskId.value, "paper_1")),
            )

            assertEquals(
                taskId,
                repository.findById(taskId)?.manifest?.taskId,
            )
            assertNull(
                repository.findById(
                    TaskId("task_9.1_theorem_9"),
                ),
            )
        }
    }

    @Test
    fun rejectsMismatchedPaperIdentifier() {
        withTemporaryDataset { datasetRoot ->
            createTaskDirectory(
                datasetRoot,
                paperId = "paper_1",
                taskId = "task_1.1_theorem_1",
            )
            val repository = repository(
                datasetRoot,
                listOf(
                    manifest(
                        taskId = "task_1.1_theorem_1",
                        paperId = "paper_2",
                    ),
                ),
            )

            val failure = assertFailsWith<IllegalStateException> {
                repository.findAll()
            }

            assertContains(
                failure.message.orEmpty(),
                "stored under paper_1",
            )
        }
    }

    @Test
    fun rejectsMissingDatasetRoot() {
        withTemporaryDataset { temporaryDirectory ->
            val missingRoot = temporaryDirectory.resolve("missing")
            val repository = repository(
                missingRoot,
                emptyList(),
            )

            val failure = assertFailsWith<IllegalStateException> {
                repository.findAll()
            }

            assertContains(
                failure.message.orEmpty(),
                "dataset root is not a directory",
            )
        }
    }

    private fun repository(
        datasetRoot: Path,
        manifests: List<TaskManifest>,
    ): FileTaskRepository {
        val manifestsByTaskId = manifests.associateBy { manifest ->
            manifest.taskId.value
        }

        return FileTaskRepository(
            datasetRoot = datasetRoot,
            manifestReader = { manifestPath ->
                manifestsByTaskId.getValue(
                    manifestPath.parent.fileName.toString(),
                )
            },
        )
    }

    private fun createTaskDirectory(
        datasetRoot: Path,
        paperId: String,
        taskId: String,
    ) {
        val taskDirectory = datasetRoot
            .resolve(paperId)
            .resolve("tasks")
            .resolve(taskId)

        Files.createDirectories(taskDirectory)
        Files.createFile(taskDirectory.resolve("manifest.json"))
    }

    private fun manifest(
        taskId: String,
        paperId: String,
    ): TaskManifest {
        return TaskManifest(
            schemaVersion = TaskManifest.SCHEMA_VERSION,
            taskId = TaskId(taskId),
            paperId = PaperId(paperId),
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
    }

    private fun withTemporaryDataset(
        block: (Path) -> Unit,
    ) {
        val datasetRoot = Files.createTempDirectory(
            "file-task-repository-",
        )

        try {
            block(datasetRoot)
        } finally {
            Files.walk(datasetRoot).use { paths ->
                paths
                    .sorted(Comparator.reverseOrder())
                    .forEach(Files::deleteIfExists)
            }
        }
    }
}
