package domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtocolIdTest {
    @Test
    fun acceptsLowerSnakeCaseIdentifier() {
        assertEquals("lean_proof", ProtocolId("lean_proof").value)
    }

    @Test
    fun rejectsMalformedIdentifiers() {
        for (value in listOf("", "LeanProof", "lean-proof", "2lean", "lean proof")) {
            assertFailsWith<IllegalArgumentException> {
                ProtocolId(value)
            }
        }
    }

    @Test
    fun rendersAsItsStringValue() {
        assertEquals("informal_proof", ProtocolId("informal_proof").toString())
    }
}
