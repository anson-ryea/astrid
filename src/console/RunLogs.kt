package console

import com.github.ajalt.mordant.rendering.Lines
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.rendering.WidthRange
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.rendering.OverflowWrap
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.HorizontalRule
import com.github.ajalt.mordant.widgets.Text
import com.github.ajalt.mordant.widgets.Viewport

/** Keeps recent milestones even while the dashboard is visible. */
internal class RunLogs {
    private val entries = ArrayDeque<String>()

    @Synchronized
    fun append(entry: RunLogEntry) {
        entries.addLast(entry.renderMessage())
        if (entries.size > 1_000) entries.removeFirst()
    }

    fun render(width: Int, height: Int, controlsEnabled: Boolean,
        status: RunDashboardStatus = RunDashboardStatus.Running): Widget {
        val columns = width.coerceAtLeast(1)
        val rows = (height - 1).coerceAtLeast(1)
        val snapshot = synchronized(this) { entries.toList() }
        val headerRows = minOf(2, (rows - 1).coerceAtLeast(0))
        val messageRows = (rows - headerRows - 1).coerceAtLeast(0)
        val messages = object : Widget {
            override fun measure(t: Terminal, width: Int) = WidthRange(width, width)
            override fun render(t: Terminal, width: Int): Lines {
                val text = if (snapshot.isEmpty()) gray("Waiting for messages") else snapshot.joinToString("\n")
                return Lines(Text(text, whitespace = Whitespace.PRE_WRAP, overflowWrap = OverflowWrap.BREAK_WORD)
                    .render(t, width).lines.takeLast(messageRows))
            }
        }
        return Viewport(verticalLayout {
            spacing = 0
            if (headerRows > 0) cell(Viewport(Text(bold("logs")), width = columns, height = 1))
            if (headerRows > 1) cell(HorizontalRule(ruleStyle = gray))
            if (messageRows > 0) cell(Viewport(messages, width = columns, height = messageRows))
            cell(Viewport(Text(when {
                status == RunDashboardStatus.Quitting -> gray("Quitting… waiting for cleanup")
                controlsEnabled -> runControls(RunConsolePage.Logs)
                else -> ""
            }), width = columns, height = 1))
        }, width = columns, height = rows)
    }
}
