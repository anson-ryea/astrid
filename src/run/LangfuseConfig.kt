package run

import java.net.URI

/** Optional export settings. Credentials never belong in a run configuration or record. */
class LangfuseConfig private constructor(
    val host: String,
    internal val publicKey: String,
    internal val secretKey: String,
    val captureContent: Boolean,
) {
    companion object {
        /** The caller supplies environment/.env precedence; no credentials means local tracing only. */
        fun fromEnvironment(value: (String) -> String?): LangfuseConfig? {
            fun setting(name: String) = value(name)?.trim()?.takeIf(String::isNotEmpty)
            fun boolean(name: String, default: Boolean): Boolean = setting(name)?.let {
                requireNotNull(it.toBooleanStrictOrNull()) { "$name must be true or false" }
            } ?: default

            if (!boolean("LANGFUSE_ENABLED", true)) return null
            val publicKey = setting("LANGFUSE_PUBLIC_KEY")
            val secretKey = setting("LANGFUSE_SECRET_KEY")
            if (publicKey == null && secretKey == null && setting("LANGFUSE_ENABLED") == null) return null
            require(publicKey != null && secretKey != null) {
                "Langfuse requires both LANGFUSE_PUBLIC_KEY and LANGFUSE_SECRET_KEY; set LANGFUSE_ENABLED=false to disable export"
            }
            val host = (setting("LANGFUSE_HOST") ?: setting("LANGFUSE_BASE_URL")
                ?: "https://cloud.langfuse.com").trimEnd('/')
            val uri = runCatching { URI(host) }.getOrNull()
            require(uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()
                && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "LANGFUSE_HOST must be an absolute HTTP(S) base URL without credentials, query or fragment"
            }
            return LangfuseConfig(host, publicKey, secretKey, boolean("LANGFUSE_CAPTURE_CONTENT", true))
        }
    }
}
