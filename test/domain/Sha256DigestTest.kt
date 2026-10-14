package domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Sha256DigestTest {
    @Test
    fun acceptsLowercaseSha256Digest() {
        val digest = "a".repeat(64)

        assertEquals(digest, Sha256Digest(digest).value)
    }

    @Test
    fun rejectsMalformedDigests() {
        val digests = listOf(
            "",
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64),
            "g".repeat(64),
            "0".repeat(63) + " ",
        )

        for (digest in digests) {
            assertFailsWith<IllegalArgumentException> {
                Sha256Digest(digest)
            }
        }
    }

    @Test
    fun rendersAsItsHexadecimalValue() {
        val digest = "0".repeat(64)

        assertEquals(digest, Sha256Digest(digest).toString())
    }
}
