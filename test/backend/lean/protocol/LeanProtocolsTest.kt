package backend.lean.protocol

import support.SimpleTestDataset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir
import protocol.withProtocolTask

class LeanProtocolsTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun usesTheSelectedDatasetsInstructionsWithSharedLeanSubmissionRules() {
        Files.writeString(temporaryDirectory.resolve("lean-proof.md"), "Instructions from a different dataset.")
        withProtocolTask("lean_proof", "lean_proof_body", mapOf(
            "lean_context" to "context-marker",
            "lean_target" to "target-marker __BENCHMARK_PROOF__",
        )) { task ->
            val protocol = LeanProtocols.create(Path.of("prompts", "lean", "v1"), temporaryDirectory).findFor(task)
            val prompt = protocol.renderPrompt(task)
            assertContains(prompt, "Instructions from a different dataset.")
            assertContains(prompt, "Return only one `by` proof body")
            assertEquals("by trivial", protocol.extractSubmission("by trivial"))
        }
    }

    @Test
    fun registersLeanProofProtocol() {
        withProtocolTask(
            protocol = "lean_proof",
            submissionKind = "lean_proof_body",
            artefacts = mapOf(
                "lean_context" to "context-marker",
                "lean_target" to "target-marker __BENCHMARK_PROOF__",
            ),
        ) { task ->
            val protocol = LeanProtocols.create(Path.of("prompts", "lean", "v1"), SimpleTestDataset.promptRoot)
                .findFor(task)

            assertContains(protocol.renderPrompt(task), "target-marker __BENCHMARK_PROOF__")
            assertEquals("by\n  trivial", protocol.extractSubmission("by\n  trivial"))
        }
    }
}
