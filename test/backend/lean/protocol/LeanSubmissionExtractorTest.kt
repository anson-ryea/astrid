package backend.lean.protocol

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LeanSubmissionExtractorTest {
    private val extractor = LeanSubmissionExtractor()

    @Test
    fun acceptsPlainLeanProof() {
        assertEquals("by\n  trivial", extractor.extractLeanProof("\nby\n  trivial\n"))
    }

    @Test
    fun acceptsOneFencedLeanProof() {
        assertEquals(
            "by\n  trivial",
            extractor.extractLeanProof("```lean\nby\n  trivial\n```"),
        )
    }

    @Test
    fun rejectsCommentaryAroundFencedProof() {
        val failure = assertFailsWith<IllegalArgumentException> {
            extractor.extractLeanProof("Here is the proof:\n```lean\nby\n  trivial\n```")
        }

        assertContains(failure.message.orEmpty(), "one Lean `by` proof body")
    }

    @Test
    fun rejectsMalformedFencedProof() {
        val failure = assertFailsWith<IllegalArgumentException> {
            extractor.extractLeanProof("```lean\nby\n  trivial\n```\n```lean\nby\n  trivial\n```")
        }

        assertContains(failure.message.orEmpty(), "only one Lean proof block")
    }

    @Test
    fun extractsFencedLeanDeclaration() {
        assertEquals(
            "inductive Target where\n  | caseOne",
            extractor.extractLeanCode("```lean\ninductive Target where\n  | caseOne\n```"),
        )
    }

    @Test
    fun rejectsEmptyLeanSubmission() {
        assertFailsWith<IllegalArgumentException> {
            extractor.extractLeanCode("  \n  ")
        }
    }

}
