package run

import domain.EvaluationResult

/** Limits compiler-feedback repairs within one sample. */
data class RepairPolicy(val maxRepairTurns: Int?) {
    init {
        require(maxRepairTurns == null || maxRepairTurns >= 0) {
            "maximum repair turns must not be negative"
        }
    }

    fun shouldRepair(repairTurn: Int, result: EvaluationResult): Boolean =
        result is EvaluationResult.Rejected && (maxRepairTurns == null || repairTurn < maxRepairTurns)
}
