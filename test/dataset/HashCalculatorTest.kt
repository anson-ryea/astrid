package dataset

import domain.Sha256Digest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class HashCalculatorTest {
    private val calculator = HashCalculator()

    @Test
    fun calculatesEmptyFileDigest() {
        withTemporaryFile("") { path ->
            assertEquals(
                Sha256Digest(
                    "e3b0c44298fc1c149afbf4c8996fb924" +
                            "27ae41e4649b934ca495991b7852b855",
                ),
                calculator.sha256(path),
            )
        }
    }

    @Test
    fun calculatesKnownTextDigest() {
        withTemporaryFile("abc") { path ->
            assertEquals(
                Sha256Digest(
                    "ba7816bf8f01cfea414140de5dae2223" +
                            "b00361a396177a9cb410ff61f20015ad",
                ),
                calculator.sha256(path),
            )
        }
    }

    private fun withTemporaryFile(
        contents: String,
        block: (Path) -> Unit,
    ) {
        val path = Files.createTempFile(
            "hash-calculator-",
            ".txt",
        )

        try {
            path.writeText(contents)
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }
}
