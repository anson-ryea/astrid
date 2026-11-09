package domain

@JvmInline
value class ContextId(val value: String) {
    init {
        require(lowerSnakeCasePattern.matches(value)) {
            "context identifier must use lower_snake_case: $value"
        }
    }

    override fun toString(): String = value

}
