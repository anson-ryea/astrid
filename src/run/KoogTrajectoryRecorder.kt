package run

import ai.koog.agents.core.feature.message.FeatureMessage
import ai.koog.agents.core.feature.message.FeatureMessageProcessor
import ai.koog.agents.core.feature.remote.defaultFeatureMessageJsonConfig
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Keeps Koog's built-in trace events for persistence with one sample. */
internal class KoogTrajectoryRecorder : FeatureMessageProcessor() {
    private val json = defaultFeatureMessageJsonConfig
    private val recorded = mutableListOf<AgentTrajectoryEvent>()

    val events: List<AgentTrajectoryEvent>
        get() = synchronized(recorded) { recorded.toList() }

    override suspend fun processMessage(message: FeatureMessage) {
        val event = AgentTrajectoryEvent(
            kind = requireNotNull(message::class.simpleName),
            timestampEpochMillis = message.timestamp,
            details = json.encodeToJsonElement<FeatureMessage>(message).jsonObject,
        )
        synchronized(recorded) { recorded += event }
    }
}
