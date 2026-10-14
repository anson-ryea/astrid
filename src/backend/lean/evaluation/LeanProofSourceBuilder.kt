package backend.lean.evaluation

import backend.lean.protocol.LeanProofProtocol.Companion.MARKER
import kotlin.text.contains

/** Inserts a proof body into a Lean target template and its context. */
class LeanProofSourceBuilder {
    fun build(context: String, target: String, submission: String): String {
        require(MARKER !in submission) {
            "submission contains the proof marker"
        }

        require(target.indexOf(MARKER) >= 0 &&
                target.indexOf(MARKER) == target.lastIndexOf(MARKER)) {
            "target template must contain exactly one proof marker"
        }

        return context + "\n" + target.replace(MARKER, submission)
    }
}
