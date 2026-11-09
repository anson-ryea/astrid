package analysis

import run.SampleBudget

class RunAnalyser {
    fun analyse(run: SavedRun, ks: List<Int> = listOf(1, 5, 10)): RunAnalysis {
        require(ks.isNotEmpty() && ks.all { it > 0 }) { "pass@k values must be positive" }
        val samplesByTask = run.samples.groupBy { it.taskId }
        val sortedKs = ks.distinct().sorted()
        val taskAcceptanceRates = samplesByTask.mapValues { (_, values) ->
            values.count { it.category == SampleCategory.ACCEPTED }.toDouble() / values.size
        }

        fun acceptanceRates(ids: List<String>) = ids.mapNotNull(taskAcceptanceRates::get)

        fun summary(dimension: String, label: String, ids: List<String>): AnalysisSummary {
            val samples = ids.flatMap { samplesByTask[it].orEmpty() }
            val complete = ids.filter { samplesByTask[it].orEmpty().size == run.samplesPerTask }
            val taskRates = acceptanceRates(ids)
            val paperRates = if (ids.all { run.taskMetadata[it] != null }) {
                ids.groupBy { run.taskMetadata.getValue(it).labels.paperId }.values.mapNotNull { paperIds ->
                    acceptanceRates(paperIds).meanOrNull()
                }
            } else emptyList()
            return AnalysisSummary(
                dimension = dimension,
                label = label,
                taskCount = ids.size,
                completeTasks = complete.size,
                expectedSamples = ids.size.toLong() * run.samplesPerTask,
                savedSamples = samples.size,
                counts = SampleCategory.entries.associateWith { category -> samples.count { it.category == category } },
                budgets = SampleBudget.entries.associate { budget -> budget.manifestValue to samples.count { it.budget == budget } },
                firstAttemptAccepted = samples.count { it.firstAttemptAccepted },
                firstAttemptAssessed = samples.count { it.firstAttemptAssessed },
                repairedAccepted = samples.count { it.category == SampleCategory.ACCEPTED && it.recordedRepairTurns > 0 },
                recordedRepairTurns = samples.sumOf { it.recordedRepairTurns.toLong() },
                samplesWithRecordedRepairs = samples.count { it.recordedRepairTurns > 0 },
                taskMacroFinalRate = taskRates.meanOrNull(),
                paperMacroFinalRate = paperRates.meanOrNull(),
                passAtK = sortedKs.map { k ->
                    val eligible = if (run.samplesPerTask >= k) complete else emptyList()
                    fun estimate(first: Boolean) = eligible.map { id ->
                        val values = samplesByTask.getValue(id)
                        passAtK(
                            values.size,
                            values.count { if (first) it.firstAttemptAccepted else it.category == SampleCategory.ACCEPTED },
                            k
                        )
                    }.meanOrNull()
                    PassAtK(k, eligible.size, estimate(true), estimate(false))
                },
                resources = ResourceSummary.from(samples),
            )
        }

        val summaries = buildList {
            add(summary("overall", "all", run.taskIds))
            for (dimension in listOf("paper", "task_type", "condition")) {
                run.taskIds.groupBy { id ->
                    run.taskMetadata[id]?.labels?.let {
                        when (dimension) {
                            "paper" -> it.paperId; "task_type" -> it.taskType.toString(); else -> it.condition
                        }
                    } ?: "unknown"
                }.toSortedMap().forEach { (label, ids) -> add(summary(dimension, label, ids)) }
            }
            run.taskIds.forEach { add(summary("task", it, listOf(it))) }
        }
        return RunAnalysis(run, summaries)
    }

    companion object {
        /** Probability that a uniformly selected subset of k samples contains a certified success. */
        fun passAtK(n: Int, successes: Int, k: Int): Double {
            require(n > 0 && successes in 0..n && k in 1..n) { "invalid pass@k counts" }
            if (successes == 0) return 0.0
            if (n - successes < k) return 1.0
            // Equivalent to 1 - C(n-c,k)/C(n,k), without factorial overflow.
            var none = 1.0
            for (i in 0 until k) none *= (n - successes - i).toDouble() / (n - i)
            return (1.0 - none).coerceIn(0.0, 1.0)
        }
    }
}

private fun List<Double>.meanOrNull(): Double? = if (isEmpty()) null else average()
