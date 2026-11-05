package run

import ai.koog.agents.features.opentelemetry.attribute.CustomAttribute
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryConfig
import ai.koog.agents.features.opentelemetry.integration.langfuse.addLangfuseSpanAdapter
import ai.koog.agents.features.opentelemetry.integration.otlp.OtlpJsonSpanExporter
import domain.BenchmarkTask
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.tracing.export.SpanProcessor
import io.opentelemetry.kotlin.tracing.export.batchSpanProcessor
import java.util.Base64

/** One Langfuse session per benchmark run, with independently owned telemetry for every sample. */
class LangfuseTracing(
    private val config: LangfuseConfig,
    private val runId: String,
    private val benchmark: String,
    private val backend: String,
) {
    @OptIn(ExperimentalApi::class)
    internal fun configure(telemetry: OpenTelemetryConfig, task: BenchmarkTask, sampleIndex: Int, modelId: String) {
        telemetry.setServiceInfo("astrid", "1")
        telemetry.setVerbose(config.captureContent)
        // Each sample installs its own SDK. Closing an agent flushes its final spans and releases HTTP resources.
        telemetry.setShutdownOnAgentClose(true)
        val auth = Base64.getEncoder().encodeToString("${config.publicKey}:${config.secretKey}".toByteArray())
        val exporter = OtlpJsonSpanExporter(
            endpoint = "${config.host}/api/public/otel/v1/traces",
            headers = mapOf(
                "Authorization" to "Basic $auth",
                "x-langfuse-ingestion-version" to "4",
            ),
        )
        telemetry.addSpanProcessor {
            val processor = batchSpanProcessor(exporter)
            // OTel Kotlin 0.3.0 shuts down the exporter before draining the batch queue.
            // Flush while its HTTP client is still open; then let shutdown release resources.
            object : SpanProcessor by processor {
                override suspend fun shutdown(): OperationResultCode {
                    val flushed = processor.forceFlush()
                    val closed = processor.shutdown()
                    return if (flushed == OperationResultCode.Success) closed else flushed
                }
            }
        }
        telemetry.addLangfuseSpanAdapter(
            traceAttributes = listOf(
                CustomAttribute("langfuse.session.id", runId),
                CustomAttribute("langfuse.trace.name", "${task.manifest.taskId.value}/$sampleIndex"),
                CustomAttribute("langfuse.trace.tags", listOf("astrid", benchmark, backend)),
                CustomAttribute("langfuse.trace.metadata.run_id", runId),
                CustomAttribute("langfuse.trace.metadata.benchmark", benchmark),
                CustomAttribute("langfuse.trace.metadata.backend", backend),
                CustomAttribute("langfuse.trace.metadata.task_id", task.manifest.taskId.value),
                CustomAttribute("langfuse.trace.metadata.paper_id", task.manifest.paperId.value),
                CustomAttribute("langfuse.trace.metadata.sample_index", sampleIndex.toLong()),
                CustomAttribute("langfuse.trace.metadata.model_id", modelId),
            ),
        )
    }
}
