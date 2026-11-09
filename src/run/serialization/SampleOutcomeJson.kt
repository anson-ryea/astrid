package run.serialization

import domain.EvaluationResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import run.SampleBudget
import run.SampleOutcome
import serialization.nonBlankString

/** Schema-5/6 outcomes have the same wire format for persistence and analysis. */
internal object SampleOutcomeJson {
    fun encode(outcome: SampleOutcome): JsonObject = buildJsonObject {
        when (outcome) {
            is SampleOutcome.Evaluated -> {
                put("kind", JsonPrimitive("evaluated"))
                put("result", EvaluationResultJson.encode(outcome.result))
            }

            is SampleOutcome.RepairLimitReached -> {
                put("kind", JsonPrimitive("repair_limit"))
                put("result", EvaluationResultJson.encode(outcome.result))
            }

            is SampleOutcome.BudgetExhausted -> {
                put("kind", JsonPrimitive("budget_exhausted"))
                put("limit", JsonPrimitive(outcome.limit.manifestValue))
                put("reason", JsonPrimitive(outcome.reason))
            }

            is SampleOutcome.ProviderFailure -> {
                put("kind", JsonPrimitive("provider_failure"))
                put("reason", JsonPrimitive(outcome.reason))
            }

            is SampleOutcome.ExecutionFailure -> {
                put("kind", JsonPrimitive("execution_failure"))
                put("reason", JsonPrimitive(outcome.reason))
            }
        }
    }


    fun decode(value: JsonObject): SampleOutcome = when (value.nonBlankString("kind")) {
        "evaluated", "repair_limit" -> {
            value.fields("kind", "result")
            val result = EvaluationResultJson.decode(value["result"])
            if (value.nonBlankString("kind") == "repair_limit") {
                require(result is EvaluationResult.Rejected) { "repair_limit must contain a rejection" }
                SampleOutcome.RepairLimitReached(result)
            } else SampleOutcome.Evaluated(result)
        }

        "budget_exhausted" -> {
            value.fields("kind", "limit", "reason")
            val reason = value.nonBlankString("reason")
            val budget = SampleBudget.entries.singleOrNull { it.manifestValue == value.nonBlankString("limit") }
                ?: throw IllegalArgumentException("unknown sample budget")
            SampleOutcome.BudgetExhausted(budget, reason)
        }

        "provider_failure", "execution_failure" -> {
            value.fields("kind", "reason")
            val reason = value.nonBlankString("reason")
            if (value.nonBlankString("kind") == "provider_failure") SampleOutcome.ProviderFailure(reason)
            else SampleOutcome.ExecutionFailure(reason)
        }

        else -> throw IllegalArgumentException("unknown sample outcome")
    }

    private fun JsonObject.fields(vararg names: String) {
        require(keys == names.toSet()) { "outcome fields disagree with its kind" }
    }
}
