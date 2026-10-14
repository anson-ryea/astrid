package dataset

import domain.Sha256Digest
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Calculates content digests for files without loading them fully into memory. */
class HashCalculator {
    /** Hashes ordered paths and contents using the dataset's V1 closure encoding. */
    fun sha256Closure(files: Map<String, String>): Sha256Digest {
        val digest = MessageDigest.getInstance(SHA_256)
        files.forEach { (path, content) ->
            for (bytes in listOf(path.toByteArray(Charsets.UTF_8), content.toByteArray(Charsets.UTF_8))) {
                digest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
                digest.update(bytes)
            }
        }
        return Sha256Digest(digest.digest().toHexadecimal())
    }

    /** Calculates the SHA-256 digest of the file at [path]. */
    fun sha256(path: Path): Sha256Digest {
        val digest = MessageDigest.getInstance(SHA_256)

        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            while (true) {
                val bytesRead = input.read(buffer)

                if (bytesRead < 0) {
                    break
                }

                digest.update(buffer, 0, bytesRead)
            }
        }

        return Sha256Digest(
            digest.digest().toHexadecimal(),
        )
    }

    private fun ByteArray.toHexadecimal(): String {
        return joinToString(separator = "") { byte ->
            byte.toUByte()
                .toString(radix = 16)
                .padStart(length = 2, padChar = '0')
        }
    }

    companion object {
        private const val SHA_256 = "SHA-256"
    }
}
