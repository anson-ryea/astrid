package run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LangfuseConfigTest {
    private val credentials = mapOf("LANGFUSE_PUBLIC_KEY" to "pk-test", "LANGFUSE_SECRET_KEY" to "sk-test")

    @Test
    fun disabledWithoutCredentialsOrWhenExplicitlyTurnedOff() {
        assertNull(LangfuseConfig.fromEnvironment { null })
        assertNull(LangfuseConfig.fromEnvironment((credentials + ("LANGFUSE_ENABLED" to "false"))::get))
        assertNull(LangfuseConfig.fromEnvironment(mapOf("LANGFUSE_ENABLED" to "false", "LANGFUSE_PUBLIC_KEY" to "pk-test")::get))
    }

    @Test
    fun requiresBothCredentialsWhenConfigured() {
        for (settings in listOf(mapOf("LANGFUSE_PUBLIC_KEY" to "pk-test"),
            mapOf("LANGFUSE_SECRET_KEY" to "sk-test"), mapOf("LANGFUSE_ENABLED" to "true"))) {
            assertFailsWith<IllegalArgumentException> { LangfuseConfig.fromEnvironment(settings::get) }
        }
    }

    @Test
    fun acceptsBothHostNamesAndMasksContentOnRequest() {
        val defaults = LangfuseConfig.fromEnvironment(credentials::get)!!
        assertEquals("https://cloud.langfuse.com", defaults.host)
        assertTrue(defaults.captureContent)
        assertFalse(defaults.toString().contains("sk-test"))
        for (name in listOf("LANGFUSE_HOST", "LANGFUSE_BASE_URL")) {
            val config = LangfuseConfig.fromEnvironment((credentials + mapOf(
                name to " http://localhost:3000/ ", "LANGFUSE_CAPTURE_CONTENT" to "false"))::get)!!
            assertEquals("http://localhost:3000", config.host)
            assertFalse(config.captureContent)
        }
        val config = LangfuseConfig.fromEnvironment((credentials + mapOf(
            "LANGFUSE_HOST" to "https://us.cloud.langfuse.com", "LANGFUSE_BASE_URL" to "http://localhost:3000"))::get)!!
        assertEquals("https://us.cloud.langfuse.com", config.host)
    }

    @Test
    fun rejectsInvalidHostsAndBooleanSettings() {
        for (host in listOf("localhost:3000", "file:///tmp/traces", "https://user:password@example.org",
            "https://example.org?secret=value", "https://example.org#fragment")) {
            assertFailsWith<IllegalArgumentException> {
                LangfuseConfig.fromEnvironment((credentials + ("LANGFUSE_HOST" to host))::get)
            }
        }
        for (name in listOf("LANGFUSE_ENABLED", "LANGFUSE_CAPTURE_CONTENT")) {
            assertFailsWith<IllegalArgumentException> {
                LangfuseConfig.fromEnvironment((credentials + (name to "yes"))::get)
            }
        }
    }
}
