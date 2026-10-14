package backend.lean.protocol

import support.SimpleTestDataset
import domain.ProtocolId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import protocol.withProtocolTask

class LeanFormalisationProtocolTest {
    private val protocols = LeanProtocols.create(Path.of("prompts", "lean", "v1"), SimpleTestDataset.promptRoot)

    @Test
    fun rendersDefinitionTask() {
        withProtocolTask(
            protocol = "lean_definition",
            submissionKind = "lean_term",
            artefacts = leanArtefacts("__BENCHMARK_TERM__"),
        ) { task ->
            val protocol = protocols.findFor(task)
            val prompt = protocol.renderPrompt(task)

            assertEquals(ProtocolId("lean_definition"), protocol.id)
            assertContains(prompt, "context-marker")
            assertContains(prompt, "target-marker __BENCHMARK_TERM__")
            assertContains(prompt, "evidence-marker")
            assertContains(prompt, "faithful to the paper")
            assertEquals("fun x => x", protocol.extractSubmission("```lean\nfun x => x\n```"))
        }
    }

    @Test
    fun rendersDeclarationTask() {
        withProtocolTask(
            protocol = "lean_declaration",
            submissionKind = "lean_declaration_block",
            artefacts = leanArtefacts("__BENCHMARK_DECLARATION__"),
        ) { task ->
            val protocol = protocols.findFor(task)
            val prompt = protocol.renderPrompt(task)

            assertContains(prompt, "target-marker __BENCHMARK_DECLARATION__")
            assertEquals("inductive Target where\n  | one", protocol.extractSubmission(
                "inductive Target where\n  | one",
            ))
        }
    }

    @Test
    fun rendersFutureStatementTask() {
        withProtocolTask(
            protocol = "lean_statement",
            submissionKind = "lean_statement",
            artefacts = leanArtefacts("__BENCHMARK_STATEMENT__"),
        ) { task ->
            val prompt = protocols.findFor(task).renderPrompt(task)

            assertContains(prompt, "target-marker __BENCHMARK_STATEMENT__")
            assertContains(prompt, "faithfulness to the paper")
        }
    }

    @Test
    fun rejectsFormalisationTaskWithoutPaperEvidence() {
        withProtocolTask(
            protocol = "lean_definition",
            submissionKind = "lean_term",
            artefacts = leanArtefacts("__BENCHMARK_TERM__") - "paper_evidence",
        ) { task ->
            val failure = assertFailsWith<IllegalArgumentException> {
                protocols.findFor(task).renderPrompt(task)
            }

            assertContains(failure.message.orEmpty(), "missing paper evidence")
        }
    }

    private fun leanArtefacts(marker: String): Map<String, String> = mapOf(
        "lean_context" to "context-marker",
        "lean_target" to "target-marker $marker",
        "paper_evidence" to "evidence-marker",
    )
}
