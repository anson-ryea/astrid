package backend.lean.protocol

/** Extracts a single Lean proof body from a model response. */
class LeanSubmissionExtractor {
    fun extractLeanProof(response: String): String {
        val proof = extractLeanCode(response, "Lean proof")
        require(proofStart.containsMatchIn(proof)) {
            "response must be one Lean `by` proof body"
        }
        return proof
    }

    fun extractLeanCode(response: String): String {
        return extractLeanCode(response, "Lean submission")
    }

    private fun extractLeanCode(response: String, label: String): String {
        val trimmed = response.trim()
        val code = if (trimmed.startsWith("```")) {
            val match = fencedLeanCode.matchEntire(trimmed)
                ?: throw IllegalArgumentException(
                    "response must contain only one $label block",
                )
            val contents = match.groupValues[1]
            require(!nestedFence.containsMatchIn(contents)) {
                "response must contain only one $label block"
            }
            contents.trim()
        } else {
            trimmed
        }

        require(code.isNotEmpty()) {
            "response must contain a $label"
        }

        return code
    }

    companion object {
        private val fencedLeanCode = Regex(
            """\A```(?:lean)?[ \t]*\r?\n(.*?)\r?\n```\z""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val nestedFence = Regex("""(?m)^[ \t]*```""")
        private val proofStart = Regex("""\Aby(?:\s|$)""")
    }
}
