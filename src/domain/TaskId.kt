package domain

@JvmInline
value class TaskId(val value: String) {
    init {
        require(pattern.matches(value)) {
            "task identifier must match task_<paper>.<task>_<original_name>: $value"
        }
    }

    override fun toString(): String = value

    companion object {
        private val pattern =
            Regex("""task_[1-9][0-9]*\.[1-9][0-9]*_[a-z][a-z0-9]*(?:_[a-z0-9]+)*""")
    }
}
