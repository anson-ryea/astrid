package domain

/** A SHA-256 digest encoded as 64 lowercase hexadecimal characters. */
@JvmInline
value class Sha256Digest(val value: String) {
    init {
        require(pattern.matches(value)) {
            "SHA-256 digest must contain exactly 64 lowercase hexadecimal characters"
        }
    }

    override fun toString(): String = value

    companion object {
        private val pattern = Regex("[0-9a-f]{64}")
    }
}
