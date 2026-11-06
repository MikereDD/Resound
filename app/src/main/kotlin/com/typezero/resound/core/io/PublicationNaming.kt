package com.typezero.resound.core.io

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Immutable intent: capture this at Record / export admission, never after processing. */
data class NamingRequest(
    val pattern: String = "",
    val instant: Instant = Instant.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
)

data class ResolvedPublicationName(val name: String?, val notice: String? = null)

/** Pure, shared by preview and publication. Null means use the original automatic policy. */
object PublicationNaming {
    private val token = Regex("\\$[A-Z]+")
    private val extensions = Regex("\\.(m4a|mp3|aac|wav|flac|ogg)$", RegexOption.IGNORE_CASE)
    /** Trailing $FORMAT (one separator before it, optional typed extension after) duplicates the extension. */
    private val trailingFormat = Regex("[_\\- .]?\\\$FORMAT(\\.[A-Za-z0-9]{1,10})?$")

    fun resolve(
        request: NamingRequest,
        ext: String,
        durationMs: Long? = null,
        trimRange: Pair<Long, Long>? = null,
    ): ResolvedPublicationName {
        if (request.pattern.isEmpty()) return ResolvedPublicationName(null)
        fun fallback(reason: String) = ResolvedPublicationName(null, "Automatic name used: $reason")
        if (!ext.matches(Regex("[A-Za-z0-9]{1,10}"))) return fallback("invalid output format.")
        fun unsafe(s: String) = s.any { it.isISOControl() || it in "/\\:*?\"<>|" } ||
            s.contains("..") || s.startsWith('.') || s.endsWith('.') || s.isBlank() ||
            s.toByteArray(Charsets.UTF_8).size > 180
        if (unsafe(request.pattern)) return fallback("unsafe or overlong filename pattern.")
        // Saved patterns keep $FORMAT; only its redundant trailing use is dropped here.
        var pattern = request.pattern
        while (true) pattern = trailingFormat.find(pattern)?.let { pattern.removeRange(it.range) + it.groupValues[1] } ?: break
        if (pattern.isBlank() || pattern.startsWith('.')) return fallback("the format is already the file extension.")
        var unavailable: String? = null
        val name = token.replace(pattern) { match ->
            val value = when (match.value) {
                "\$DATE" -> DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US).withZone(request.zone).format(request.instant)
                "\$TIME" -> DateTimeFormatter.ofPattern("HHmmss", Locale.US).withZone(request.zone).format(request.instant)
                "\$FORMAT" -> ext.lowercase(Locale.ROOT)
                "\$DURATION" -> durationMs?.takeIf { it > 0 }?.let(::time)
                "\$TRIM" -> trimRange?.takeIf { it.first >= 0 && it.second > it.first }
                    ?.let { "${time(it.first)}--${time(it.second)}" }
                else -> null
            }
            if (value == null) unavailable = match.value
            value ?: match.value
        }
        if (unavailable != null || name.contains('$')) return fallback("${unavailable ?: "token"} unavailable or unknown.")
        val result = if (name.endsWith(".$ext", ignoreCase = true)) name
            else extensions.replace(name, "") + ".${ext.lowercase(Locale.ROOT)}"
        if (unsafe(result)) return fallback("unsafe or overlong resolved filename.")
        return ResolvedPublicationName(result)
    }

    /** Called on IO using the completed OUTPUT, never a source-duration estimate. */
    fun atCreation(
        request: NamingRequest,
        ext: String,
        readDuration: () -> Long?,
        trimRange: Pair<Long, Long>? = null,
    ): ResolvedPublicationName {
        val duration = if (request.pattern.contains("\$DURATION")) {
            try { readDuration() } catch (_: Exception) { null }
        } else null
        return resolve(request, ext, duration, trimRange)
    }

    private fun time(ms: Long): String = String.format(Locale.US, "%02d-%02d-%02d.%03d",
        ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
}
