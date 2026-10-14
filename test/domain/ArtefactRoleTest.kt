package domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArtefactRoleTest {
    @Test
    fun acceptsLowerSnakeCaseRoles() {
        val roles = listOf(
            "lean_context",
            "lean_target",
            "paper_evidence",
        )

        for (role in roles) {
            assertEquals(role, ArtefactRole(role).value)
        }
    }

    @Test
    fun rejectsMalformedRoles() {
        val roles = listOf(
            "",
            "LeanContext",
            "lean-context",
            "lean context",
            "2lean_context",
            "../private",
        )

        for (role in roles) {
            assertFailsWith<IllegalArgumentException> {
                ArtefactRole(role)
            }
        }
    }

    @Test
    fun rendersAsItsStringValue() {
        assertEquals(
            "lean_context",
            ArtefactRole("lean_context").toString(),
        )
    }
}
