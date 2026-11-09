package console

import com.github.ajalt.mordant.rendering.OverflowWrap
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextStyles.Companion.hyperlink
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.underline
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.widgets.HorizontalRule
import com.github.ajalt.mordant.widgets.Text
import text.singleLine
import java.nio.file.Path

/** Static details shared by the dashboard info page and saved-run reports. */
internal class InfoLayout(private val width: Int) {
    private val rows = mutableListOf<Widget>()

    fun line(value: String) {
        rows += Text(value, whitespace = Whitespace.PRE_WRAP, overflowWrap = OverflowWrap.BREAK_WORD)
    }

    fun rule() {
        rows += HorizontalRule(ruleStyle = gray)
    }

    fun section(title: String, gap: Boolean = true) {
        if (gap) line("")
        line((bold + underline)(title))
    }

    fun value(label: String, value: String) {
        val key = gray(if (width >= 60) label.padEnd(22) else label)
        line("  $key  $value")
    }

    fun setting(label: String, setting: Any?, absent: String = "unlimited") {
        value(label, green(singleLine(setting?.toString() ?: absent)))
    }

    fun path(label: String, path: Path?) {
        value(
            label, if (path == null) gray("not supplied") else {
                val absolute = path.toAbsolutePath().normalize()
                (green + underline + hyperlink(absolute.toUri().toASCIIString()))(singleLine(absolute.toString()))
            }
        )
    }

    fun render(): Widget = verticalLayout {
        spacing = 0
        rows.forEach { cell(it) }
    }
}
