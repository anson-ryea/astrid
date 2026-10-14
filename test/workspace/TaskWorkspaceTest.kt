package workspace

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class TaskWorkspaceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun acceptsBackendSuppliedSubmissionFilesAndProtectsInputsEvenWhenScratchRulesOverlap() {
        val root = Files.createDirectory(temporaryDirectory.resolve("sample"))
        val input = TaskWorkspace.snapshotFile("scratch/prerequisite.txt", "fixed input")
        TaskWorkspace(root, listOf(input), "context", "closure",
            WorkspaceSpecification(mapOf("Answer.thy" to "initial theory"), Regex("scratch/[A-Za-z]+\\.txt")),
        ).use { workspace ->
            workspace.write("Answer.thy", "submitted theory")
            workspace.write("scratch/notes.txt", "working notes")
            assertEquals("submitted theory", workspace.read("Answer.thy"))
            assertEquals(listOf("Answer.thy", "scratch/notes.txt"), workspace.snapshot().map { it.path })
            assertFailsWith<IllegalArgumentException> { workspace.write(input.path, "changed") }
            assertEquals("fixed input", workspace.read(input.path))
            assertEquals(null, workspace.changedInput())
            Files.writeString(root.resolve(input.path), "external change")
            assertEquals(input.path, workspace.changedInput())
        }
    }

    @Test
    fun rejectsUnsafeBackendSuppliedInitialPaths() {
        val root = Files.createDirectory(temporaryDirectory.resolve("sample"))
        assertFailsWith<IllegalArgumentException> {
            TaskWorkspace(root, emptyList(), "context", "closure",
                WorkspaceSpecification(mapOf("../Answer.txt" to "bad"), Regex("scratch/.+")))
        }
        assertEquals(false, Files.exists(temporaryDirectory.resolve("Answer.txt")))
    }
}
