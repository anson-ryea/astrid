package prompt

import java.nio.file.Path
import kotlin.io.path.readText

/** Renders a versioned prompt template without expanding task content as placeholders. */
class PromptRenderer(private val templatePath: Path) {
    fun render(values: Map<String, String>): String {
        val template = templatePath.readText()

        return placeholder.replace(template) { match ->
            val name = match.groupValues[1]
            values[name]
                ?: throw IllegalArgumentException(
                    "prompt template requires a value for $name: $templatePath",
                )
        }
    }

    companion object {
        private val placeholder = Regex("""\{\{([A-Z][A-Z0-9_]*)}}""")
    }
}
