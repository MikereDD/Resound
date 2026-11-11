/*
 * file:    TimelineViewport.kt
 * desc:    Bounded horizontal timeline geometry for the Multitrack workspace. Arrangement positions
 *          stay in absolute time (milliseconds); only viewport-local pixel values ever become Compose
 *          layout sizes or offsets, so no measured width grows with duration x zoom (Compose cannot
 *          represent widths beyond about 262,143 px).
 */
package com.typezero.resound.feature.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Horizontal timeline position as absolute arrangement time: the millisecond at the viewport's left edge.
 * Storing time instead of pixels keeps the same moment at the left edge when the zoom changes, and keeps
 * positions exact for long arrangements (a Double holds every millisecond of any realistic arrangement).
 * [leftMs] may exceed the current maximum after a zoom or edit; readers clamp it with [effectiveLeftMs].
 */
@Stable
internal class TimelineScrollState(initialLeftMs: Double = 0.0) {
    var leftMs by mutableDoubleStateOf(finiteOrZero(initialLeftMs).coerceAtLeast(0.0))
        private set

    /** Scrolls by [deltaPx] (positive = later in time) and returns the pixels actually consumed. */
    fun scrollBy(deltaPx: Float, pxPerSec: Float, maxLeftMs: Double): Float {
        val step = timelineScrollStep(leftMs, deltaPx, pxPerSec, maxLeftMs)
        leftMs = step.leftMs
        return step.consumedPx
    }

    /**
     * Clamps the stored position to [maxLeftMs]. Called when the arrangement's duration changes, so a view that
     * was past a shortened end does not jump back to the old time when the arrangement grows again. Zoom
     * changes do not call this: they keep the left-edge time.
     */
    fun clampTo(maxLeftMs: Double) {
        leftMs = effectiveLeftMs(leftMs, maxLeftMs)
    }

    companion object {
        val Saver: Saver<TimelineScrollState, Double> = Saver(save = { it.leftMs }, restore = { TimelineScrollState(it) })
    }
}

@Composable
internal fun rememberTimelineScrollState(initialLeftMs: Double = 0.0): TimelineScrollState =
    rememberSaveable(saver = TimelineScrollState.Saver) { TimelineScrollState(initialLeftMs) }

private fun finiteOrZero(value: Double): Double = if (value.isFinite()) value else 0.0
private fun validScale(pxPerSec: Float): Boolean = pxPerSec.isFinite() && pxPerSec > 0f

/** Absolute pixels from the arrangement start to [ms]. Double: 12 h at 240 px/s is 10,368,000 px. Invalid input -> 0. */
internal fun timelinePx(ms: Double, pxPerSec: Float): Double =
    if (!validScale(pxPerSec) || !ms.isFinite()) 0.0 else ms * pxPerSec / 1000.0

/**
 * The rendered scroll offset: the exact position snapped to whole pixels, as ScrollState.value was, so the
 * ruler, lane grid and clip boxes all share one integral offset (no sub-pixel shimmer or relative drift).
 */
internal fun renderedScrollPx(leftMs: Double, maxLeftMs: Double, pxPerSec: Float): Double =
    round(timelinePx(effectiveLeftMs(leftMs, maxLeftMs), pxPerSec))

/** Latest left-edge time: the arrangement end sits at the viewport's right edge; 0 when everything fits. */
internal fun timelineMaxLeftMs(durationMs: Long, pxPerSec: Float, viewportPx: Float): Double {
    if (!validScale(pxPerSec) || durationMs <= 0L) return 0.0
    return (durationMs.toDouble() - viewportPx.coerceAtLeast(0f) * 1000.0 / pxPerSec).coerceAtLeast(0.0)
}

internal fun effectiveLeftMs(leftMs: Double, maxLeftMs: Double): Double =
    finiteOrZero(leftMs).coerceIn(0.0, finiteOrZero(maxLeftMs).coerceAtLeast(0.0))

internal data class TimelineScrollStep(val leftMs: Double, val consumedPx: Float)

/**
 * One scroll step. An unclamped step consumes exactly the requested pixels (no rounding drift between
 * finger and content); a step that hits either end consumes only the remaining distance.
 */
internal fun timelineScrollStep(leftMs: Double, deltaPx: Float, pxPerSec: Float, maxLeftMs: Double): TimelineScrollStep {
    val current = effectiveLeftMs(leftMs, maxLeftMs)
    if (!validScale(pxPerSec) || !deltaPx.isFinite() || deltaPx == 0f) return TimelineScrollStep(current, 0f)
    val wanted = current + deltaPx * 1000.0 / pxPerSec
    val target = wanted.coerceIn(0.0, finiteOrZero(maxLeftMs).coerceAtLeast(0.0))
    if (target == wanted) return TimelineScrollStep(target, deltaPx)
    val consumed = timelinePx(target - current, pxPerSec).toFloat()
    return TimelineScrollStep(target, if (abs(consumed) > abs(deltaPx)) deltaPx else consumed)
}

/**
 * Index of the viewport-wide slice that contains [scrollPx]. Clips are composed per slice, so scrolling
 * recomposes the lanes once per screen width instead of on every frame.
 */
internal fun timelineWindowIndex(scrollPx: Double, viewportPx: Float): Long =
    if (viewportPx < 1f || !scrollPx.isFinite()) 0L else floor(scrollPx.coerceAtLeast(0.0) / viewportPx).toLong()

/**
 * Absolute pixel range composed for slice [index]: one viewport behind and three ahead (4 viewports wide).
 * Any viewport inside the slice stays at least one viewport away from both window edges, so cut clip
 * edges are never on screen.
 */
internal fun timelineWindowPx(index: Long, viewportPx: Float): ClosedFloatingPointRange<Double> {
    val viewport = viewportPx.coerceAtLeast(1f).toDouble()
    val slice = index.toDouble() // Double: index + 3 cannot overflow even for a saturated index
    return ((slice - 1) * viewport)..((slice + 3) * viewport)
}

/** Minimum drawn clip width (unchanged): very short clips stay visible and grabbable. */
internal const val MIN_CLIP_PX = 24f

/**
 * The part of a clip laid out for the current window, in absolute pixels. [hiddenLeftPx] is how much of
 * the clip lies left of the laid-out box; drawing and drag-edge detection add it back, so the clip's real
 * edges and waveform stay at their absolute time positions. [widthPx] is at most 4 viewports + 1.
 */
internal data class ClipBoxSpan(val leftPx: Double, val widthPx: Int, val hiddenLeftPx: Double, val fullWidthPx: Double)

/** The clip's laid-out span inside [window], or null when the clip lies entirely outside it. */
internal fun clipBoxSpan(startMs: Long, lengthMs: Long, pxPerSec: Float, window: ClosedFloatingPointRange<Double>): ClipBoxSpan? {
    if (!validScale(pxPerSec)) return null
    val clipLeft = timelinePx(startMs.toDouble(), pxPerSec)
    val fullWidth = max(timelinePx(lengthMs.coerceAtLeast(0L).toDouble(), pxPerSec), MIN_CLIP_PX.toDouble())
    val left = max(clipLeft, window.start)
    val right = min(clipLeft + fullWidth, window.endInclusive)
    if (!(right > left)) return null
    return ClipBoxSpan(leftPx = left, widthPx = ceil(right - left).toInt().coerceAtLeast(1),
        hiddenLeftPx = left - clipLeft, fullWidthPx = fullWidth)
}

/**
 * Drag mode for a touch at [boxX] inside the laid-out box: 1 = trim in, 2 = trim out, 0 = move. Edges are
 * the clip's real edges, so a long clip cut at the window edge is moved, not trimmed, when touched there.
 */
internal fun clipDragMode(boxX: Float, span: ClipBoxSpan, edgePx: Float): Int {
    val clipX = boxX + span.hiddenLeftPx
    return when {
        clipX < edgePx -> 1
        clipX > span.fullWidthPx - edgePx -> 2
        else -> 0
    }
}

/**
 * Waveform buckets (0-based, relative to the clip's first bucket) worth drawing for a box that shows clip-local
 * x in [hiddenLeftPx, hiddenLeftPx + boxWidthPx], plus [padPx] on each side. Bucket i sits at x = i * step with
 * step = fullWidthPx / buckets. Empty when there is nothing to draw.
 */
internal fun visibleBucketRange(hiddenLeftPx: Double, boxWidthPx: Float, fullWidthPx: Double, buckets: Int, padPx: Float): IntRange {
    if (buckets <= 0 || !(fullWidthPx > 0.0) || !hiddenLeftPx.isFinite()) return IntRange.EMPTY
    val step = fullWidthPx / buckets
    val first = floor((hiddenLeftPx - padPx) / step).toLong().coerceIn(0L, (buckets - 1).toLong()).toInt()
    val last = ceil((hiddenLeftPx + boxWidthPx + padPx) / step).toLong().coerceIn(first.toLong(), (buckets - 1).toLong()).toInt()
    return first..last
}

/** Whole seconds whose marks fall in [scrollPx - marginPx, scrollPx + viewportPx + marginPx], clamped to the arrangement. */
internal fun visibleSecondsRange(scrollPx: Double, viewportPx: Float, marginPx: Float, pxPerSec: Float, lastSecond: Long): LongRange {
    if (!validScale(pxPerSec) || !scrollPx.isFinite()) return LongRange.EMPTY
    val first = floor((scrollPx - marginPx) / pxPerSec).toLong().coerceAtLeast(0L)
    val last = floor((scrollPx + viewportPx + marginPx) / pxPerSec).toLong().coerceAtMost(lastSecond)
    return first..last
}
