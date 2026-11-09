package analysis

/** Every saved sample stays in the denominator, including uncertainty and execution failures. */
data class PassAtK(val k: Int, val eligibleTasks: Int, val firstAttempt: Double?, val final: Double?)
