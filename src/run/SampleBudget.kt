package run

/** Limits that can interrupt an attempt before assessment. Repair limits retain a rejection. */
enum class SampleBudget(val manifestValue: String) {
    OUTPUT_TOKENS("output_tokens"),
    TOTAL_TOKENS("total_tokens"),
    SAMPLE_TIME("sample_time"),
    AGENT_ITERATIONS("agent_iterations"),
    TOOL_CALLS("tool_calls"),
    WORKSPACE_TOOL_CALLS("workspace_tool_calls"),
}
