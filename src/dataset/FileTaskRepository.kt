package dataset

import domain.BenchmarkTask
import domain.TaskId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

/** Discovers task packages beneath the paper directories of a dataset root. */
class FileTaskRepository(
    datasetRoot: Path,
    private val manifestReader: TaskManifestReader,
): TaskRepository {
    private val datasetRoot =
        datasetRoot.toAbsolutePath().normalize()

    override fun findAll(): List<BenchmarkTask> {
        check(datasetRoot.isDirectory()) {
            "dataset root is not a directory: $datasetRoot"
        }

        val tasks = childDirectories(datasetRoot)
            .filter { paperDirectory ->
                paperDirectory.fileName
                    .toString()
                    .startsWith("paper_")
            }
            .flatMap { paperDirectory ->
                childDirectories(
                    paperDirectory.resolve("tasks"),
                ).map { taskDirectory ->
                    loadTask(
                        paperDirectory = paperDirectory,
                        taskDirectory = taskDirectory,
                    )
                }
            }

        val duplicateTaskIds = tasks
            .groupingBy { task -> task.manifest.taskId }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys

        check(duplicateTaskIds.isEmpty()) {
            "duplicate task identifiers: " +
                    duplicateTaskIds
                        .sortedBy(TaskId::value)
                        .joinToString()
        }

        return tasks.sortedBy { task ->
            task.manifest.taskId.value
        }
    }

    override fun findById(taskId: TaskId): BenchmarkTask? {
        return findAll().singleOrNull { task ->
            task.manifest.taskId == taskId
        }
    }

    private fun childDirectories(parent: Path): List<Path> {
        if (!parent.isDirectory()) {
            return emptyList()
        }

        return Files.list(parent).use { paths ->
            paths
                .filter { path -> path.isDirectory() }
                .toList()
        }
    }

    private fun loadTask(
        paperDirectory: Path,
        taskDirectory: Path,
    ): BenchmarkTask {
        val manifest = manifestReader.read(
            taskDirectory.resolve("manifest.json"),
        )

        check(
            manifest.paperId.value ==
                    paperDirectory.fileName.toString(),
        ) {
            "task ${manifest.taskId} declares paper " +
                    "${manifest.paperId}, but is stored under " +
                    paperDirectory.fileName
        }

        return BenchmarkTask(
            manifest = manifest,
            packageDirectory = taskDirectory
                .toAbsolutePath()
                .normalize(),
        )
    }
}
