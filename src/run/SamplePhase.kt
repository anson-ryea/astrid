package run

/** The current activity of one independent sample, including any repair attempts. */
enum class SamplePhase {
    Preparing,
    Generating,
    Evaluating,
    Repairing,
}
