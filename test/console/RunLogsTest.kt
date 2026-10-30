package console

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunLogsTest {
    @Test
    fun followsWrappedMessagesWithinTheScreenAndSanitisesTerminalControls() {
        val logs = RunLogs()
        logs.append(RunLogEntry("oldest milestone"))
        for (i in 1..30) logs.append(RunLogEntry("message $i: " + "a long explanation ".repeat(3)))
        logs.append(RunLogEntry("newest\u001b[2J\nmessage"))
        for (width in listOf(20, 80, 160)) for (height in listOf(2, 3, 4, 8, 24)) {
            val terminal = Terminal(width = width, terminalInterface = TerminalRecorder(ansiLevel = AnsiLevel.NONE))
            val frame = logs.render(width, height, controlsEnabled = true)
            assertTrue(frame.render(terminal, width).height <= height - 1)
            val text = terminal.render(frame)
            assertTrue(text.lines().all { it.length <= width })
            assertContains(text.lines().last(), "l dashboard")
            assertFalse(text.contains("oldest milestone"))
            assertFalse(text.contains('\u001b'))
            if (height >= 8) {
                assertContains(text.lines().first(), "logs")
                assertContains(text.lines()[1], "─")
                assertContains(text.replace(Regex("\\s+"), " "), "newest [2J message")
            }
        }
    }
}
