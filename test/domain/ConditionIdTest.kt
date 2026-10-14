package domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConditionIdTest {
    @Test
    fun acceptsLowerSnakeCaseIdentifiers() {
        val identifiers = listOf(
            "paper_evidence",
            "formal_context_only",
            "enriched_case_by_case_evidence",
        )

        for (identifier in identifiers) {
            assertEquals(identifier, ConditionId(identifier).value)
        }
    }

    @Test
    fun rejectsMalformedIdentifiers() {
        val identifiers = listOf(
            "",
            "PaperEvidence",
            "paper-evidence",
            "paper evidence",
            "2paper_evidence",
            "../private",
        )

        for (identifier in identifiers) {
            assertFailsWith<IllegalArgumentException> {
                ConditionId(identifier)
            }
        }
    }

    @Test
    fun rendersAsItsStringValue() {
        assertEquals(
            "paper_evidence",
            ConditionId("paper_evidence").toString(),
        )
    }
}
