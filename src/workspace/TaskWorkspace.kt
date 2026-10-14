package workspace

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Bounded file storage and snapshots, independent of the backend and agent framework. */
class TaskWorkspace internal constructor(
    val root: Path,
    private val inputs: List<WorkspaceFile>,
    private val contextId: String,
    private val closureSha256: String,
    private val specification: WorkspaceSpecification,
) : AutoCloseable {
    private val writableFiles = sortedSetOf<String>()
    private val inputPaths = inputs.map { it.path }.toSet()

    init {
        require(root.isAbsolute && root == root.normalize()) { "workspace root must be absolute and normalised" }
        require(inputPaths.size == inputs.size) { "workspace input paths must be unique" }
        require(specification.initialFiles.keys.none { it in inputPaths }) {
            "editable files must not overlap workspace inputs"
        }
        inputs.forEach { file ->
            val destination = resolve(file.path)
            Files.createDirectories(destination.parent)
            Files.writeString(destination, file.content)
        }
        specification.initialFiles.forEach { (path, content) -> write(path, content) }
    }

    @Synchronized
    fun visibleFiles(): List<String> = (inputPaths + writableFiles).sorted()

    @Synchronized
    fun read(path: String): String {
        require(path in inputPaths || path in writableFiles) { "file is not visible: $path" }
        return Files.readString(resolve(path))
    }

    @Synchronized
    fun write(path: String, content: String) {
        require(path !in inputPaths &&
            (path in specification.initialFiles || specification.scratchPath.matches(path))) {
            "file is read-only or not writable: $path"
        }
        val destination = resolve(path)
        require(content.toByteArray(Charsets.UTF_8).size <= specification.maxFileBytes) {
            "file exceeds ${specification.maxFileBytes} bytes"
        }
        require(path in writableFiles || writableFiles.size < specification.maxWritableFiles) { "too many writable files" }
        Files.createDirectories(destination.parent)
        Files.writeString(destination, content)
        writableFiles += path
    }

    fun changedInput(): String? = inputs.firstOrNull { read(it.path) != it.content }?.path

    @Synchronized
    fun snapshot(): List<WorkspaceFile> = writableFiles.map { path -> snapshotFile(path, read(path)) }

    fun record(): WorkspaceRecord = WorkspaceRecord(contextId, closureSha256, inputs, snapshot())

    /** Resolve validated relative paths without following symlinks, including directory ancestors for listings. */
    internal fun resolve(path: String, directory: Boolean = false): Path {
        require((directory && path.isEmpty()) ||
            (safePath.matches(path) && path.split('/').none { it == "." || it == ".." })) { "invalid relative path" }
        val result = root.resolve(path).normalize()
        require(result.startsWith(root)) { "path escapes workspace" }
        var current = root
        require(!Files.isSymbolicLink(current)) { "workspace is a symbolic link" }
        for (segment in root.relativize(result)) {
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "symbolic links are not accessible" }
            if (current != result && Files.exists(current, NOFOLLOW_LINKS)) {
                require(Files.isDirectory(current, NOFOLLOW_LINKS)) { "parent is not a directory" }
            }
        }
        if (Files.exists(result, NOFOLLOW_LINKS)) {
            require(if (directory) Files.isDirectory(result, NOFOLLOW_LINKS) else Files.isRegularFile(result, NOFOLLOW_LINKS)) {
                "path is not a regular ${if (directory) "directory" else "file"}"
            }
        }
        return result
    }

    override fun close() {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    companion object {
        private val safePath = Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
        internal fun snapshotFile(path: String, content: String): WorkspaceFile = WorkspaceFile(
            path,
            MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
            content,
        )
    }
}
