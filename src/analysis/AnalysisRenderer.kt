package analysis

import analysis.rendering.CsvAnalysisRenderer
import analysis.rendering.JsonAnalysisRenderer
import analysis.rendering.TextAnalysisRenderer

/** Stable report entry points; each format owns its rendering details. */
object AnalysisRenderer {
    const val DENOMINATOR_POLICY =
        "All saved samples are included. NeedsReview and failures are not certified successes; counts remain separate. Missing samples are reported separately."
    const val PASS_POLICY =
        "1-C(n-c,k)/C(n,k), averaged over fully saved tasks with n>=k; first-attempt and final sample successes are separate. No failure exclusions."

    fun json(analyses: List<RunAnalysis>): String = JsonAnalysisRenderer.render(analyses)
    fun text(analyses: List<RunAnalysis>): String = TextAnalysisRenderer.render(analyses)
    fun csv(analyses: List<RunAnalysis>): String = CsvAnalysisRenderer.render(analyses)
}
