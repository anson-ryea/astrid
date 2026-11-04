package command

import java.nio.file.Path
import kotlin.io.path.Path

/** A plain name uses the output root; explicit native path syntax bypasses it. */
internal fun isRunPath(value: String): Boolean {
    val path = Path(value)
    return path.isAbsolute || path.root != null || path.parent != null ||
        value == "." || value == ".." || value.endsWith('/') || value.endsWith(path.fileSystem.separator)
}

/** Resolve with the host filesystem's rules, including Windows drives and UNC roots. */
internal fun resolveRunDirectory(workingDirectory: Path, outputRoot: Path, runIdOrPath: String): Path {
    val path = Path(runIdOrPath)
    val directory = if (isRunPath(runIdOrPath)) workingDirectory.resolve(path)
        else workingDirectory.resolve(outputRoot).resolve(path)
    return directory.normalize().also {
        require(it.isAbsolute) {
            "run output must resolve to an absolute path; use a full drive path instead of a path relative to another drive"
        }
    }
}
