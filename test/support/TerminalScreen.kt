package support

/** A small screen model for the cursor movements, colours and hyperlinks emitted by Mordant. */
internal class TerminalScreen(private val width: Int, private val height: Int) {
    private val rows = MutableList(height) { CharArray(width) { ' ' } }
    val history = mutableListOf<String>()
    val lines: List<String> get() = rows.map { it.concatToString() }
    private var x = 0
    private var y = 0

    fun write(output: String) {
        val withoutLinks = Regex("\u001B][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
            .replace(output, "")
        val tokens = Regex("\u001b\\[[0-?]*[ -/]*[@-~]|[\\s\\S]")
        for (match in tokens.findAll(withoutLinks)) {
            val token = match.value
            if (token.startsWith("\u001b[")) {
                val argument = token.substring(2, token.lastIndex).toIntOrNull() ?: 0
                when (token.last()) {
                    'A' -> y = (y - argument.coerceAtLeast(1)).coerceAtLeast(0)
                    'B' -> y = (y + argument.coerceAtLeast(1)).coerceAtMost(height - 1)
                    'C' -> x = (x + argument.coerceAtLeast(1)).coerceAtMost(width - 1)
                    'D' -> x = (x - argument.coerceAtLeast(1)).coerceAtLeast(0)
                    'J' -> when (argument) {
                        0 -> {
                            rows[y].fill(' ', x.coerceAtMost(width), width)
                            for (row in y + 1 until height) rows[row].fill(' ')
                        }
                        2 -> rows.forEach { it.fill(' ') }
                    }
                    'K' -> rows[y].fill(' ', if (argument == 2) 0 else x.coerceAtMost(width), width)
                    // SGR and cursor visibility change appearance, not the screen position.
                }
            } else when (val character = token.single()) {
                '\r' -> x = 0
                '\n' -> newline()
                else -> {
                    if (x >= width) newline()
                    rows[y][x++] = character
                }
            }
        }
    }

    private fun newline() {
        x = 0
        if (y == height - 1) {
            history += rows.removeAt(0).concatToString()
            rows += CharArray(width) { ' ' }
        } else y++
    }
}
