package backend.lean

import domain.ArtefactPath
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** File access for trusted Lean inputs, including every directory ancestor. */
internal object LeanInputFiles {
    fun root(path: Path): Path = path.toAbsolutePath().normalize().let {
        if (Files.exists(it)) it.toRealPath() else it
    }

    fun path(root: Path, relative: String): Path {
        val path = root.resolve(ArtefactPath(Path.of(relative)).value)
        require(path.startsWith(root) && Files.isRegularFile(path, NOFOLLOW_LINKS) &&
            path.toRealPath() == path && path.toRealPath().startsWith(root)) {
            "unsafe or missing Lean input: $relative"
        }
        return path
    }

    fun read(root: Path, relative: String): String = Files.readString(path(root, relative))
}
