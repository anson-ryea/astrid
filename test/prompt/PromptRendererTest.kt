package prompt

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromptRendererTest {
    @Test
    fun replacesTemplatePlaceholdersOnce() {
        val template = Files.createTempFile("prompt-renderer-", ".md")
        try {
            Files.writeString(template, "Target: {{TARGET}}")

            val rendered = PromptRenderer(template).render(
                mapOf("TARGET" to "{{UNCHANGED}}"),
            )

            assertEquals("Target: {{UNCHANGED}}", rendered)
        } finally {
            Files.deleteIfExists(template)
        }
    }

    @Test
    fun rejectsMissingTemplateValue() {
        val template = Files.createTempFile("prompt-renderer-", ".md")
        try {
            Files.writeString(template, "Target: {{TARGET}}")

            val failure = assertFailsWith<IllegalArgumentException> {
                PromptRenderer(template).render(emptyMap())
            }

            assertContains(failure.message.orEmpty(), "requires a value for TARGET")
        } finally {
            Files.deleteIfExists(template)
        }
    }
}
