package domain

@JvmInline
value class ArtefactRole(val value: String) {
    init {
        require(lowerSnakeCasePattern.matches(value)) {
            "artefact role must use lower_snake_case: $value"
        }
    }

    override fun toString(): String = value

}
