package domain

/** An open identifier for the protocol used to run and evaluate a task. */
@JvmInline
value class ProtocolId(val value: String) {
    init {
        require(pattern.matches(value)) {
            "protocol identifier must use lower_snake_case: $value"
        }
    }

    override fun toString(): String = value

    companion object {
        private val pattern = Regex("[a-z][a-z0-9_]*")
    }
}
