/*
 * file:    TrimMath.kt
 * desc:    Pure clip trim arithmetic (no Android types) so invariants are unit-testable.
 *          Invariants for any clip with a known duration D > 0:
 *            0 <= inMs <= outMs <= D, outMs - inMs >= min(MIN_CLIP_MS, D), startMs >= 0.
 *          Unknown/zero duration clips are rejected at import; trims on them are no-ops.
 */
package com.typezero.resound.feature.timeline

const val MIN_CLIP_MS = 200L

data class ClipSpan(val startMs: Long, val inMs: Long, val outMs: Long)

object TrimMath {
    fun minLength(sourceDurationMs: Long): Long = minOf(MIN_CLIP_MS, sourceDurationMs.coerceAtLeast(0L))

    /**
     * Move the left edge by [delta]. Source-in and timeline start move by ONE shared
     * adjustment, clamped against both limits, so the clip's right edge on the
     * timeline never moves.
     */
    fun trimIn(span: ClipSpan, delta: Long, sourceDurationMs: Long): ClipSpan {
        if (sourceDurationMs <= 0L) return span
        val out = span.outMs.coerceIn(0L, sourceDurationMs)
        val maxIn = (out - minLength(sourceDurationMs)).coerceAtLeast(0L)
        val lo = maxOf(-span.inMs, -span.startMs).coerceAtMost(0L)
        val hi = maxIn - span.inMs
        val adj = minOf(maxOf(delta, lo), maxOf(hi, lo))
        return span.copy(startMs = span.startMs + adj, inMs = span.inMs + adj, outMs = out)
    }

    /** Move the right edge by [delta], clamped to [in + minLength, duration]. */
    fun trimOut(span: ClipSpan, delta: Long, sourceDurationMs: Long): ClipSpan {
        if (sourceDurationMs <= 0L) return span
        val hi = sourceDurationMs
        val lo = minOf(span.inMs + minLength(sourceDurationMs), hi)
        val newOut = minOf(maxOf(span.outMs + delta, lo), hi)
        return span.copy(outMs = newOut)
    }
}
