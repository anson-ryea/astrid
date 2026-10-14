package run

import kotlinx.serialization.json.JsonObject

/** One Koog trace event, retaining its native event name, timestamp and serialised payload. */
data class AgentTrajectoryEvent(
    val kind: String,
    val timestampEpochMillis: Long,
    val details: JsonObject,
)
