package com.typezero.resound.core.io

/**
 * Checkbox helpers over the existing [PublicationNaming] token syntax. The pattern text stays the
 * single source of truth: a box is checked exactly when its token is present, so manually typed
 * tokens stay in sync. Toggling only adds or removes that token (and one adjacent separator).
 */
enum class FilenameOption(val token: String, val label: String) {
    DATE("\$DATE", "Date"),
    TIME("\$TIME", "Time"),
    DURATION("\$DURATION", "Duration"),
    TRIM("\$TRIM", "Trim range"),
}

object FilenameOptions {
    private const val SEPARATORS = "_- "

    private fun occurrences(option: FilenameOption) = Regex(Regex.escape(option.token) + "(?![A-Z])")

    fun isSelected(pattern: String, option: FilenameOption): Boolean = occurrences(option).containsMatchIn(pattern)

    fun toggle(pattern: String, option: FilenameOption, selected: Boolean): String =
        if (selected) add(pattern, option) else remove(pattern, option)

    /** Appends once, joined with `_` unless the text already ends with a separator. */
    private fun add(pattern: String, option: FilenameOption): String = when {
        isSelected(pattern, option) -> pattern
        pattern.isEmpty() -> option.token
        pattern.last() in SEPARATORS -> pattern + option.token
        else -> pattern + "_" + option.token
    }

    /** Removes every occurrence with one preceding (else following) separator; other text is kept. */
    private fun remove(pattern: String, option: FilenameOption): String {
        var text = pattern
        while (true) {
            val match = occurrences(option).find(text) ?: break
            var start = match.range.first
            var end = match.range.last + 1
            if (start > 0 && text[start - 1] in SEPARATORS) start--
            else if (end < text.length && text[end] in SEPARATORS) end++
            text = text.removeRange(start, end)
        }
        return if (text.all { it in SEPARATORS }) "" else text
    }

    /** Example values for previews only; never used for publication. */
    const val SAMPLE_DURATION_MS = 65_000L
    val SAMPLE_TRIM_RANGE = 1_000L to 6_000L
}
