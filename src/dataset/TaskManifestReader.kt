package dataset

import domain.TaskManifest
import java.nio.file.Path

/** Reads and validates task manifests from storage. */
fun interface TaskManifestReader {
    /** Reads the manifest at [manifestPath]. */
    fun read(manifestPath: Path): TaskManifest
}
