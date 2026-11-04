package command

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/** These cases exercise the native Windows NIO provider without accessing any drive or share. */
@EnabledOnOs(OS.WINDOWS)
class RunOutputPathWindowsTest {
    private val working = Path("C:\\work\\astrid")

    @Test
    fun distinguishesRunNamesFromExplicitWindowsPaths() {
        assertFalse(isRunPath("run-a"))
        for (value in listOf("C:\\results\\run-a", "C:/results/run-a", "\\\\server\\share\\run-a",
            ".\\run-a", "..\\run-a", "results\\run-a", "run-a\\", "C:run-a", "\\run-a")) {
            assertTrue(isRunPath(value), value)
        }
    }

    @Test
    fun absoluteDriveAndUncPathsBypassTheOutputRoot() {
        for (value in listOf("C:\\results\\run-a", "D:\\results\\run-a", "D:/results/run-a",
            "\\\\server\\share\\results\\run-a")) {
            assertEquals(Path(value), resolveRunDirectory(working, Path("ignored"), value))
        }
    }

    @Test
    fun relativePathsAndBothSeparatorStylesUseTheWorkingDirectory() {
        val cases = mapOf(
            ".\\run-a" to "C:\\work\\astrid\\run-a",
            "results\\run-a" to "C:\\work\\astrid\\results\\run-a",
            "results/run-a" to "C:\\work\\astrid\\results\\run-a",
            "..\\run-a" to "C:\\work\\run-a",
            "run-a\\" to "C:\\work\\astrid\\run-a",
            "C:run-a" to "C:\\work\\astrid\\run-a",
            "\\run-a" to "C:\\run-a",
        )
        for ((value, expected) in cases) {
            assertEquals(Path(expected), resolveRunDirectory(working, Path("D:\\ignored"), value), value)
        }
    }

    @Test
    fun namesUseRelativeOrAbsoluteOutputRoots() {
        assertEquals(Path("C:\\work\\astrid\\runs\\run-a"),
            resolveRunDirectory(working, Path("runs"), "run-a"))
        assertEquals(Path("D:\\results\\run-a"),
            resolveRunDirectory(working, Path("D:\\results"), "run-a"))
    }

    @Test
    fun rejectsPathsThatRemainRelativeToAnotherDrive() {
        assertFailsWith<IllegalArgumentException> {
            resolveRunDirectory(working, Path("runs"), "D:run-a")
        }
        assertFailsWith<IllegalArgumentException> {
            resolveRunDirectory(working, Path("D:results"), "run-a")
        }
    }
}
