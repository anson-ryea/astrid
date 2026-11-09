package text

/** Prevents control characters and Unicode line separators from introducing extra display lines. */
internal fun singleLine(text: String): String = text.map {
    if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it
}.joinToString("")
