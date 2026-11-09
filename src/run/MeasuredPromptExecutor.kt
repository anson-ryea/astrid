package run

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlin.time.TimeSource

/** Counts each logical client call and applies sample-wide budget settings across tools and repairs. */
internal class MeasuredPromptExecutor(
    private val delegate: PromptExecutor,
    private val specification: BenchmarkRunSpecification,
    private val sampleIndex: Int,
    private val resources: SampleResourceRecorder,
    private val exhaust: (SampleBudget, String) -> Nothing,
) : PromptExecutor() {
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        val remaining = specification.limits.maxTotalTokens?.let { limit ->
            resources.requireTotalUsage()
            (limit - resources.total).also { if (it <= 0) exhaust(SampleBudget.TOTAL_TOKENS, "sample total-token budget exhausted") }
        }
        val cap = remaining?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()?.let { allowed ->
            prompt.params.maxTokens?.coerceAtMost(allowed) ?: allowed
        } ?: prompt.params.maxTokens
        val request = prompt.copy(params = specification.generation.params(sampleIndex, cap))
        resources.request(cap)
        val started = TimeSource.Monotonic.markNow()
        try {
            val response = delegate.execute(request, model, tools)
            resources.response(response.metaInfo)
            return response
        } finally {
            resources.modelMs += started.elapsedNow().inWholeMilliseconds
        }
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        error("benchmark sampling requires non-streaming requests for resource accounting")
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("moderation is not a benchmark request")
    override fun close() = Unit // The run owns the shared delegate.
}
