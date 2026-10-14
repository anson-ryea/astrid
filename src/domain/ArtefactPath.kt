package domain

import java.nio.file.Path

/** A relative artefact path that cannot escape its task package. */
@JvmInline
value class ArtefactPath(val value: Path) {
    init {
        require(isSafeRelativePath(value)) {
            "artefact path must be a safe relative path: $value"
        }
    }

    override fun toString(): String = value.toString()

    companion object {
        private val segmentPattern =
            Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

        private fun isSafeRelativePath(path: Path): Boolean {
            return !(path.toString().isBlank() || path.isAbsolute) && path.all { segment ->
                val segmentName = segment.toString()

                segmentName != "." &&
                        segmentName != ".." &&
                        segmentPattern.matches(segmentName)
            }
        }
    }
}
