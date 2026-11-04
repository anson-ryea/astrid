package console

import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyles.bold

internal enum class RunConsolePage { Dashboard, Logs, Info }

internal fun runControls(page: RunConsolePage): String {
    fun key(key: String, label: String) = bold(key) + gray(" $label")
    val links = when (page) {
        RunConsolePage.Dashboard -> listOf(key("l", "logs"), key("i", "info"))
        RunConsolePage.Logs -> listOf(key("l", "dashboard"), key("i", "info"))
        RunConsolePage.Info -> listOf(key("i", "dashboard"), key("l", "logs"), key("↑↓", "scroll"))
    }
    return (links + key("q", "quit") + key("Q", "discard")).joinToString(gray(" · "))
}
