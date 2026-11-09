package domain

@JvmInline
value class EvaluationKind(val value: String) {
    init {
        require(lowerSnakeCasePattern.matches(value)) {
            "evaluation kind must use lower_snake_case: $value"
        }
    }

    override fun toString(): String = value

}
