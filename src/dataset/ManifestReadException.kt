package dataset

import java.nio.file.Path

/** Reports why a manifest at [manifestPath] could not be read or decoded. */
class ManifestReadException(
    val manifestPath: Path,
    val reason: String,
    cause: Throwable? = null,
) : RuntimeException(
    "failed to read task manifest $manifestPath: $reason",
    cause,
)
