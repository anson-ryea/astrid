package domain

@JvmInline
value class ConditionId(val value: String) {
    init {
        require(lowerSnakeCasePattern.matches(value)) {
            "condition identifier must use lower_snake_case: $value"
        }
    }

    override fun toString(): String = value

}
