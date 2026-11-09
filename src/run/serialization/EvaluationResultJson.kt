package run.serialization

import domain.EvaluationResult
import kotlinx.serialization.json.*
import serialization.nonBlankString

internal object EvaluationResultJson {
    fun encode(result: EvaluationResult?): JsonElement =
        if (result == null) JsonNull else buildJsonObject {
            when (result) {
                EvaluationResult.Accepted ->
                    put("kind", JsonPrimitive("accepted"))

                is EvaluationResult.Rejected -> {
                    put("kind", JsonPrimitive("rejected"))
                    put("reason", JsonPrimitive(result.reason))
                }

                is EvaluationResult.NeedsReview -> {
                    put("kind", JsonPrimitive("needs_review"))
                    put("reason", JsonPrimitive(result.reason))
                }

                is EvaluationResult.InfrastructureFailure -> {
                    put("kind", JsonPrimitive("infrastructure_failure"))
                    put("reason", JsonPrimitive(result.reason))
                }
            }
        }


    fun decode(value: JsonElement?): EvaluationResult {
        require(value is JsonObject) { "evaluation verdict must be an object" }
        val kind = value.nonBlankString("kind")
        val reason = if (kind != "accepted") value.nonBlankString("reason") else null
        return when (kind) {
            "accepted" -> EvaluationResult.Accepted
            "rejected" -> EvaluationResult.Rejected(requireNotNull(reason))
            "needs_review" -> EvaluationResult.NeedsReview(requireNotNull(reason))
            "infrastructure_failure" -> EvaluationResult.InfrastructureFailure(requireNotNull(reason))
            else -> throw IllegalArgumentException("unknown evaluation verdict: $kind")
        }
    }
}
