package run

import domain.TaskId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import run.serialization.RunRecordJson
import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

/** Saves one run and its samples in a new directory. */
class FileRunStore(runDirectory: Path) : RunStore {
    private val runDirectory = runDirectory.toAbsolutePath().normalize()
    private val json = Json { prettyPrint = true }

    private var activeSpecification: BenchmarkRunSpecification? = null
    private var selectedTaskIds: List<TaskId> = emptyList()
    private var selectedTaskMetadata: Map<TaskId, RunTaskMetadata> = emptyMap()
    private val savedSamples = mutableSetOf<Pair<TaskId, Int>>()
    private var finished = false
    private var createdDirectory = false
    private var directoryKey: Any? = null

    override suspend fun begin(
        specification: BenchmarkRunSpecification,
        taskIds: List<TaskId>,
        taskMetadata: Map<TaskId, RunTaskMetadata>,
    ) {
        check(activeSpecification == null) { "run has already begun" }
        require(taskIds.isNotEmpty()) { "run must select at least one task" }
        require(taskIds.distinct().size == taskIds.size) {
            "selected task IDs must be unique"
        }
        require(taskMetadata.keys.all { it in taskIds }) { "metadata contains unselected task IDs" }
        selectedTaskMetadata = taskMetadata.toMap()
        val parent = requireNotNull(runDirectory.parent) {
            "run directory must have a parent"
        }
        check(Files.isDirectory(parent)) {
            "run directory parent does not exist: $parent"
        }

        // createDirectory fails if the run ID has already been used.
        withContext(Dispatchers.IO) {
            Files.createDirectory(runDirectory)
            createdDirectory = true
            directoryKey = Files.readAttributes(runDirectory, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        }
        writeAtomically(
            target = runDirectory.resolve("run.json"),
            value = RunRecordJson.run(specification, taskIds, "running", selectedTaskMetadata),
            replace = false,
        )

        activeSpecification = specification
        selectedTaskIds = taskIds.toList()
    }

    override suspend fun save(record: TaskRunRecord) {
        val specification = checkNotNull(activeSpecification) {
            "run has not begun"
        }
        check(!finished) { "run has already finished" }
        check(record.taskId in selectedTaskIds) {
            "task was not selected: ${record.taskId}"
        }
        check(record.sampleIndex in 1..specification.samplesPerTask) {
            "sample index is outside the configured range"
        }

        val key = record.taskId to record.sampleIndex
        check(key !in savedSamples) {
            "sample has already been saved: $key"
        }

        val sampleDirectory = runDirectory
            .resolve("tasks")
            .resolve(record.taskId.value)
            .resolve("samples")
            .resolve(record.sampleIndex.toString().padStart(3, '0'))
        val outputDirectory = sampleDirectory.resolve("output")
        val outputFiles = record.workspace?.finalFiles.orEmpty().map { file ->
            val relativePath = Path.of(file.path)
            require(
                file.path.isNotBlank() && !relativePath.isAbsolute &&
                        relativePath.none { it.toString() == "." || it.toString() == ".." }) {
                "output file must have a safe relative path: ${file.path}"
            }
            outputDirectory.resolve(relativePath) to file.content
        }
        require(outputFiles.map { it.first }.distinct().size == outputFiles.size) {
            "output file paths must be unique"
        }

        withContext(Dispatchers.IO) {
            Files.createDirectories(sampleDirectory)
            val recordFile = sampleDirectory.resolve("record.json")
            val trajectoryFile = sampleDirectory.resolve("trajectory.jsonl")
            // Check all destinations before writing any artefacts.
            val destinations = listOf(recordFile, trajectoryFile) +
                    if (record.workspace != null) listOf(outputDirectory) else emptyList()
            for (target in destinations) {
                check(Files.notExists(target)) { "run file already exists: $target" }
            }
            if (record.workspace != null) {
                Files.createDirectory(outputDirectory)
                for ((target, content) in outputFiles) {
                    Files.createDirectories(target.parent)
                    writeAtomically(target, content, replace = false)
                }
            }
            writeTrajectoryAtomically(trajectoryFile, record)
            // Publish the sample summary only after its outputs and trace exist.
            writeAtomically(
                target = recordFile,
                value = RunRecordJson.sample(record),
                replace = false,
            )
        }
        savedSamples += key
    }

    override suspend fun complete() {
        val specification = checkNotNull(activeSpecification) {
            "run has not begun"
        }
        check(!finished) { "run has already finished" }

        val expected =
            selectedTaskIds.size.toLong() * specification.samplesPerTask
        check(savedSamples.size.toLong() == expected) {
            "run has ${savedSamples.size} of $expected samples"
        }

        writeAtomically(
            target = runDirectory.resolve("run.json"),
            value = RunRecordJson.run(specification, selectedTaskIds, "complete", selectedTaskMetadata),
            replace = true,
        )
        finished = true
    }

    override suspend fun fail(reason: String) {
        require(reason.isNotBlank()) { "failure reason must not be blank" }
        val specification = checkNotNull(activeSpecification) {
            "run has not begun"
        }
        check(!finished) { "run has already finished" }

        writeAtomically(
            target = runDirectory.resolve("run.json"),
            value = RunRecordJson.run(specification, selectedTaskIds, "failed", selectedTaskMetadata, reason),
            replace = true,
        )
        finished = true
    }

    /** Discards only the folder created by this store. Call after all run workers have stopped. */
    internal suspend fun discard(): Boolean = withContext(Dispatchers.IO) {
        if (!createdDirectory) return@withContext false
        check(!Files.isSymbolicLink(runDirectory.parent)) { "run directory parent was replaced by a symbolic link" }
        val attributes = Files.readAttributes(runDirectory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attributes.isDirectory && (directoryKey == null || attributes.fileKey() == directoryKey)) {
            "run directory was replaced; refusing to discard unrelated output: $runDirectory"
        }
        // walkFileTree does not follow symbolic links, including links inside a saved workspace.
        Files.walkFileTree(runDirectory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
        createdDirectory = false
        true
    }

    private fun writeTrajectoryAtomically(target: Path, record: TaskRunRecord) =
        writeAtomically(target, replace = false) { writer ->
            for (event in record.trajectory) {
                writer.write(Json.encodeToString(JsonObject.serializer(), RunRecordJson.trajectoryEvent(record, event)))
                writer.write("\n")
            }
        }

    private fun writeAtomically(
        target: Path,
        value: JsonElement,
        replace: Boolean,
    ) = writeAtomically(
        target,
        json.encodeToString(JsonElement.serializer(), value) + "\n",
        replace,
    )

    private fun writeAtomically(
        target: Path,
        value: String,
        replace: Boolean,
    ) = writeAtomically(target, replace) { it.write(value) }

    private fun writeAtomically(
        target: Path,
        replace: Boolean,
        write: (BufferedWriter) -> Unit,
    ) {
        if (!replace) {
            check(Files.notExists(target)) {
                "run file already exists: $target"
            }
        }

        val temporary = Files.createTempFile(
            target.parent,
            ".${target.fileName}.",
            ".tmp",
        )

        try {
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use(write)

            if (replace) {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
