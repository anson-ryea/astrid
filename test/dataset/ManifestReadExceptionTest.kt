package dataset

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ManifestReadExceptionTest {
    @Test
    fun preservesManifestPathReasonAndCause() {
        val manifestPath = Path(
            "dataset",
            "paper_1",
            "tasks",
            "task_1.1_theorem_11",
            "manifest.json",
        )
        val cause = IllegalStateException("invalid JSON")

        val exception = ManifestReadException(
            manifestPath = manifestPath,
            reason = "manifest is not valid JSON",
            cause = cause,
        )

        assertEquals(manifestPath, exception.manifestPath)
        assertEquals(
            "manifest is not valid JSON",
            exception.reason,
        )
        assertSame(cause, exception.cause)
        assertEquals(
            "failed to read task manifest $manifestPath: " +
                    "manifest is not valid JSON",
            exception.message,
        )
    }
}
