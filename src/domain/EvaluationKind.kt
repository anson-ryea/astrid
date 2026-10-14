package domain

@JvmInline
value class EvaluationKind(val value: String) {
    init {
        require(pattern.matches(value)) {
            "evaluation kind must use lower_snake_case: $value"
        }
    }

    override fun toString(): String = value

    companion object {
        private val pattern = Regex("[a-z][a-z0-9_]*")
    }
}
