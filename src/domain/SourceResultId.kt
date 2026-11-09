package domain

@JvmInline
value class SourceResultId(val value: String) {
    init {
        require(lowerSnakeCasePattern.matches(value)) {
            "source result identifier must use lower_snake_case: $value"
        }
    }

    override fun toString(): String = value

}
