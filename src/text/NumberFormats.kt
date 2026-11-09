package text

import java.util.*

internal fun formatPercentage(value: Double?): String =
    value?.let { String.format(Locale.ROOT, "%.2f%%", it * 100) } ?: "n/a"

internal fun formatDecimal(value: Double?): String =
    value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "n/a"
