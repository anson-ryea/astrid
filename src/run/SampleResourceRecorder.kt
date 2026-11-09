package run

import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.time.TimeSource

internal class SampleResourceRecorder(private val pricing: TokenPricing?, private val seed: Int?) {
    private val started = TimeSource.Monotonic.markNow()
    val elapsedMs: Long get() = started.elapsedNow().inWholeMilliseconds
    var modelMs = 0L
    var toolMs = 0L
    var evaluationMs = 0L
    private val caps = mutableListOf<Int?>()
    fun request(cap: Int?) {
        requests++
        caps += cap
    }

    var requests = 0
    var responses = 0
    var toolCalls = 0
    var workspaceToolCalls = 0
    private var input = 0L
    private var output = 0L
    var total = 0L
        private set
    private var inputCoverage = 0
    private var outputCoverage = 0
    private var totalCoverage = 0

    fun response(info: ResponseMetaInfo) {
        responses++
        val inTokens = info.inputTokensCount?.also { require(it >= 0) { "negative provider input usage" } }?.toLong()
        val outTokens = info.outputTokensCount?.also { require(it >= 0) { "negative provider output usage" } }?.toLong()
        val totalTokens = info.totalTokensCount?.also { require(it >= 0) { "negative provider total usage" } }?.toLong()
            ?: if (inTokens != null && outTokens != null) inTokens + outTokens else null
        inTokens?.let {
            input = Math.addExact(input, it)
            inputCoverage++
        }
        outTokens?.let {
            output = Math.addExact(output, it)
            outputCoverage++
        }
        totalTokens?.let {
            total = Math.addExact(total, it)
            totalCoverage++
        }
    }

    fun requireTotalUsage() {
        check(totalCoverage == responses) { "provider omitted total usage; configured token budget cannot be enforced" }
    }

    fun snapshot(): SampleResources {
        val inputTotal = input.takeIf { inputCoverage == requests }
        val outputTotal = output.takeIf { outputCoverage == requests }
        val estimate = if (pricing != null && inputTotal != null && outputTotal != null)
            (inputTotal / 1_000_000.0 * pricing.inputUsdPerMillion + outputTotal / 1_000_000.0 * pricing.outputUsdPerMillion).takeIf(
                Double::isFinite
            )
        else null
        return SampleResources(
            elapsedMs, modelMs, toolMs, evaluationMs, requests, responses, toolCalls, workspaceToolCalls,
            inputTotal, outputTotal, total.takeIf { totalCoverage == requests }, input, output, total,
            inputCoverage, outputCoverage, totalCoverage, seed, estimate, caps.toList()
        )
    }
}
