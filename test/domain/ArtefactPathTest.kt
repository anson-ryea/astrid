package domain

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArtefactPathTest {
    @Test
    fun acceptsSafeRelativePaths() {
        val paths = listOf(
            Path("Context.lean"),
            Path("evidence.md"),
            Path("inputs", "paper-proof.md"),
            Path("formalisation", "theorem_11.lean"),
        )

        for (path in paths) {
            assertEquals(path, ArtefactPath(path).value)
        }
    }

    @Test
    fun rejectsUnsafePaths() {
        val paths = listOf(
            Path(""),
            Path("/Context.lean"),
            Path("..", "private", "reference.proof"),
            Path("inputs", "..", "private"),
            Path(".", "Context.lean"),
            Path("C:private"),
            Path("evidence file.md"),
        )

        for (path in paths) {
            assertFailsWith<IllegalArgumentException> {
                ArtefactPath(path)
            }
        }
    }

    @Test
    fun rendersAsItsPathString() {
        val path = Path("Context.lean")

        assertEquals(path.toString(), ArtefactPath(path).toString())
    }
}
