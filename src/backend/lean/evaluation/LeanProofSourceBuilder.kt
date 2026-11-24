package backend.lean.evaluation

import backend.lean.protocol.LeanProofProtocol.Companion.MARKER
import kotlinx.serialization.json.JsonPrimitive

/** Inserts a proof body into a Lean target template and its context. */
class LeanProofSourceBuilder {
    fun build(context: String, target: String, submission: String): String {
        require(
            target.indexOf(MARKER) >= 0 &&
                    target.indexOf(MARKER) == target.lastIndexOf(MARKER)
        ) {
            "target template must contain exactly one proof marker"
        }

        return context + "\n" + target.replace(MARKER, submission)
    }

    /** Parse in the target's notation scope. Only the quoted string is model input to this probe. */
    fun tokenProbe(context: String, target: String, submission: String): String {
        val probe = """
            by
              run_tac
                let input := ${JsonPrimitive(submission)}
                let tree ← Lean.ofExcept (Lean.Parser.runParserCategory (← Lean.getEnv) `term input)
                let mut cursor := tree.getPos?.getD 0
                let mut code := ""
                for token in tree.topDown (firstChoiceOnly := true) do
                  if token.isAtom || token.isIdent then
                    if let .original _ _ trailing _ := token.getHeadInfo then
                      if cursor ≤ trailing.startPos && trailing.startPos < trailing.stopPos then
                        code := code ++ cursor.extract input trailing.startPos ++ " "
                        cursor := trailing.stopPos
                IO.println ("${LeanSubmissionPolicy.TOKEN_PREFIX}" ++ (Lean.toJson (code ++ cursor.extract input input.rawEndPos)).compress)
              sorry
        """.trimIndent()
        return build("import Lean\n$context", target, probe)
    }
}
