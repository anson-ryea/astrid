package backend.lean.evaluation

import kotlin.time.Duration

/** Runs Lean on complete source text, independently of any task protocol. */
fun interface LeanProcess {
    fun run(source: String, timeout: Duration): LeanProcessResult
}
