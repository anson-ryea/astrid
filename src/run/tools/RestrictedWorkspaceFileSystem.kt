package run.tools

import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import workspace.TaskWorkspace

/** Koog sees virtual absolute paths, never the host filesystem or its temporary paths. */
internal class RestrictedWorkspaceFileSystem(private val workspace: TaskWorkspace) : FileSystemProvider.ReadWrite<String> {
    override fun fromAbsolutePathString(path: String): String {
        val relative = relative(path)
        return if (relative.isEmpty()) ROOT else "$ROOT/$relative"
    }

    override fun toAbsolutePathString(path: String): String = fromAbsolutePathString(path)

    override fun joinPath(base: String, vararg parts: String): String {
        var path = fromAbsolutePathString(base)
        for (part in parts) {
            require(!part.startsWith('/')) { "joined paths must be relative" }
            path = fromAbsolutePathString("$path/$part")
        }
        return path
    }

    override fun name(path: String): String = fromAbsolutePathString(path).substringAfterLast('/')

    override fun extension(path: String): String = name(path).substringAfterLast('.', "")

    override fun parent(path: String): String? =
        fromAbsolutePathString(path).takeUnless { it == ROOT }?.substringBeforeLast('/')

    override fun relativize(root: String, path: String): String? {
        val base = fromAbsolutePathString(root)
        val target = fromAbsolutePathString(path)
        return when {
            target == base -> ""
            target.startsWith("$base/") -> target.removePrefix("$base/")
            else -> null
        }
    }

    override suspend fun metadata(path: String): FileMetadata? {
        val relative = relative(path)
        val files = workspace.visibleFiles()
        val type = when {
            relative in files -> FileMetadata.FileType.File
            relative.isEmpty() || relative == "scratch" || files.any { it.startsWith("$relative/") } ->
                FileMetadata.FileType.Directory
            else -> return null
        }
        val hostPath = workspace.resolve(relative, directory = type == FileMetadata.FileType.Directory)
        return if (Files.exists(hostPath, NOFOLLOW_LINKS)) FileMetadata(type, hidden = false) else null
    }

    override suspend fun exists(path: String): Boolean = metadata(path) != null

    override suspend fun list(directory: String): List<String> {
        require(metadata(directory)?.type == FileMetadata.FileType.Directory) { "not a visible directory: $directory" }
        val relative = relative(directory)
        val prefix = if (relative.isEmpty()) "" else "$relative/"
        // Derive the tree from the allowlist, rather than enumerating host directories.
        return (workspace.visibleFiles() + "scratch")
            .filter { it.startsWith(prefix) && it != relative }
            .map { "$ROOT/$prefix" + it.removePrefix(prefix).substringBefore('/') }
            .distinct().sorted().filter { metadata(it) != null }
    }

    override suspend fun readBytes(path: String): ByteArray = workspace.read(relative(path)).encodeToByteArray()

    override suspend fun inputStream(path: String): Source = Buffer().apply { write(readBytes(path)) }

    override suspend fun size(path: String): Long = readBytes(path).size.toLong()

    override suspend fun getFileContentType(path: String): FileMetadata.FileContentType {
        require(metadata(path)?.type == FileMetadata.FileType.File) { "not a visible file: $path" }
        return FileMetadata.FileContentType.Text
    }

    override suspend fun writeBytes(path: String, data: ByteArray) {
        workspace.write(relative(path), data.decodeToString(throwOnInvalidSequence = true))
    }

    // The registered built-in tools write through writeBytes. Disable unused mutation APIs.
    override suspend fun create(path: String, type: FileMetadata.FileType): Unit = unsupported()
    override suspend fun move(source: String, target: String): Unit = unsupported()
    override suspend fun copy(source: String, target: String): Unit = unsupported()
    override suspend fun delete(path: String): Unit = unsupported()
    override suspend fun outputStream(path: String, append: Boolean): Sink = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("workspace supports only bounded text writes")

    private fun relative(path: String): String {
        val absolute = path.removeSuffix("/")
        require(absolute == ROOT || absolute.startsWith("$ROOT/")) { "path must be inside $ROOT" }
        val relative = absolute.removePrefix(ROOT).removePrefix("/")
        require(relative.isEmpty() || (safePath.matches(relative) && relative.split('/').none { it == "." || it == ".." })) {
            "invalid workspace path"
        }
        return relative
    }

    companion object {
        const val ROOT = "/workspace"
        private val safePath = Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
    }
}
