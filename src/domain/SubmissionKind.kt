package domain

@JvmInline
value class SubmissionKind(val value: String) {
    init {
        require(lowerSnakeCasePattern.matches(value)) {
            "submission kind must use lower_snake_case: $value"
        }
    }

    override fun toString(): String = value

}
