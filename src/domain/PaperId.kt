package domain

@JvmInline
value class PaperId(val value: String) {
    init {
        require(pattern.matches(value)) {
            "paper identifier must match paper_<number>: $value"
        }
    }

    override fun toString(): String = value

    companion object {
        private val pattern = Regex("paper_[1-9][0-9]*")
    }
}
