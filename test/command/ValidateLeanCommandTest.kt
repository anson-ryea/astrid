package command

import com.github.ajalt.clikt.command.parse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class ValidateLeanCommandTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test fun honoursExternalDatasetSelectionWithoutProviderCredentials() = runBlocking {
        val root = temporaryDirectory.toRealPath()
        Files.createDirectory(root.resolve("dataset"))
        val dataset = Files.createDirectory(root.resolve("external"))
        val failure = assertFails {
            ValidateLeanCommand(root).parse(listOf("--dataset-root", dataset.toString(), "--paper-id", "paper_37"))
        }
        assertContains(failure.message.orEmpty(), "no selected tasks")
        assertFalse(Files.exists(root.resolve("runs")))
    }

    @Test fun rejectsInvalidPaperIdentifiersBeforeReadingTasks() = runBlocking {
        val root = temporaryDirectory.toRealPath()
        Files.createDirectory(root.resolve("dataset"))
        val failure = assertFails { ValidateLeanCommand(root).parse(listOf("--paper-id", "../private")) }
        assertContains(failure.message.orEmpty(), "paper identifier")
    }
}
