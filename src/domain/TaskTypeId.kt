package domain

/** An open, positive identifier for a benchmark task type. */
@JvmInline
value class TaskTypeId(val value: Int) {
    init {
        require(value > 0) {
            "task type identifier must be positive: $value"
        }
    }

    override fun toString(): String = value.toString()
}
