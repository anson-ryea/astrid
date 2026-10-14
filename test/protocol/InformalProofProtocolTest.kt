package protocol

import support.SimpleTestDataset
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import prompt.PromptRenderer

class InformalProofProtocolTest {
    private val protocols = TaskProtocolRegistry(listOf(InformalProofProtocol(
        PromptRenderer(SimpleTestDataset.promptRoot.resolve("informal-proof.md")),
    )))

    @Test
    fun extractsProseWithoutDependingOnTheLeanExtractor() {
        val protocol = InformalProofProtocol(PromptRenderer(Path.of("unused-template.md")))
        assertEquals("By induction on the derivation.", protocol.extractSubmission("\nBy induction on the derivation.\n"))
        assertFailsWith<IllegalArgumentException> { protocol.extractSubmission(" \n ") }
    }

    @Test
    fun rendersPaperStatementAndOptionalEvidence() {
        withProtocolTask(
            protocol = "informal_proof",
            submissionKind = "informal_proof",
            artefacts = mapOf(
                "paper_statement" to "statement-marker",
                "paper_evidence" to "evidence-marker",
            ),
        ) { task ->
            val protocol = protocols.findFor(task)
            val prompt = protocol.renderPrompt(task)

            assertContains(prompt, "statement-marker")
            assertContains(prompt, "evidence-marker")
            assertEquals("By induction.", protocol.extractSubmission(" By induction. "))
        }
    }

    @Test
    fun rendersStatementWithoutEvidence() {
        withProtocolTask(
            protocol = "informal_proof",
            submissionKind = "informal_proof",
            artefacts = mapOf("paper_statement" to "statement-marker"),
        ) { task ->
            val prompt = protocols.findFor(task).renderPrompt(task)

            assertContains(prompt, "statement-marker")
            assertFalse(prompt.contains("## Evidence"))
        }
    }

    @Test
    fun rejectsMissingPaperStatement() {
        withProtocolTask(
            protocol = "informal_proof",
            submissionKind = "informal_proof",
            artefacts = mapOf("paper_evidence" to "evidence-marker"),
        ) { task ->
            val failure = assertFailsWith<IllegalArgumentException> {
                protocols.findFor(task).renderPrompt(task)
            }

            assertContains(failure.message.orEmpty(), "exactly one paper_statement")
        }
    }
}
