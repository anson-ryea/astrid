package run

internal fun requireSampleCounts(samplesPerTask: Int, maxConcurrentTasks: Int) {
    require(samplesPerTask > 0) { "samples per task must be positive" }
    require(maxConcurrentTasks > 0) { "maximum concurrent tasks must be positive" }
}

internal fun requireToolCallLimits(other: Int?, workspace: Int?) {
    require(other == null || other >= 0) { "maximum tool calls per attempt must not be negative" }
    require(workspace == null || workspace >= 0) { "maximum workspace tool calls per attempt must not be negative" }
}

internal fun requireAgentIterationLimit(limit: Int?) {
    require(limit == null || limit > 0) { "maximum agent iterations must be positive" }
}

internal fun requireOutputTokenLimit(limit: Long?) {
    require(limit == null || limit in 1..Int.MAX_VALUE.toLong()) { "maximum output tokens must fit Koog's request limit" }
}
