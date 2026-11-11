package com.typezero.resound.feature.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Phase 5 width-limit regression: bounded timeline geometry. Deterministic host tests of the pure helpers
 * that decide every timeline layout size, placement and draw range; no Compose runtime involved.
 */
class TimelineViewportTest {
    /** Compose cannot represent a constraint width above 2^18 - 1 px (with a small height). */
    private val composeWidthLimit = 262_143

    private data class Scenario(val name: String, val durationMs: Long, val pxPerSec: Float)

    private val hour = 3_600_000L
    private val scenarios = listOf(
        Scenario("empty (display minimum)", timelineDisplayDurationMs(emptyList()), 24f),
        Scenario("30 s @ 24", 30_000L, 24f),
        Scenario("18 min @ 240", 18 * 60_000L, 240f),
        Scenario("20 min @ 240", 20 * 60_000L, 240f),
        Scenario("3 h @ 24", 3 * hour, 24f),
        Scenario("3 h @ 240", 3 * hour, 240f),
        Scenario("12 h @ 240", 12 * hour, 240f),
        Scenario("12 h @ 6 (min zoom)", 12 * hour, 6f),
    )
    /** Lane viewport widths in px: 320 dp phone at 1x up to a 3x landscape tablet. */
    private val viewports = listOf(187f, 339f, 837f, 3120f)

    /** Same layout as the debug long-arrangement fixture: start, middle, end and one full-length clip. */
    private fun clipsFor(totalMs: Long): List<Pair<Long, Long>> {
        val len = minOf(30_000L, totalMs / 4)
        return listOf(0L to len, (totalMs / 2 - len / 2) to len, 0L to totalMs, (totalMs - len) to len)
    }

    @Test fun everyLaidOutWidthStaysBoundedAtEveryScrollPosition() {
        for (s in scenarios) for (viewport in viewports) {
            val maxLeft = timelineMaxLeftMs(s.durationMs, s.pxPerSec, viewport)
            val positions = (0..100).map { maxLeft * it / 100.0 } + listOf(0.0, maxLeft / 2, maxLeft, maxLeft + 1e9)
            for (requested in positions) {
                val scroll = timelinePx(effectiveLeftMs(requested, maxLeft), s.pxPerSec)
                val window = timelineWindowPx(timelineWindowIndex(scroll, viewport), viewport)
                assertEquals("${s.name}: window is 4 viewports", 4.0 * viewport, window.endInclusive - window.start, 1e-6)
                assertTrue("${s.name}: viewport keeps one viewport of margin on the left", window.start <= scroll - viewport + 1e-6)
                assertTrue("${s.name}: viewport keeps one viewport of margin on the right", window.endInclusive >= scroll + 2 * viewport - 1e-6)
                for ((start, length) in clipsFor(s.durationMs)) {
                    val span = clipBoxSpan(start, length, s.pxPerSec, window) ?: continue
                    assertTrue("${s.name} @ $viewport: clip width ${span.widthPx} bounded", span.widthPx <= 4 * viewport + 1)
                    assertTrue("${s.name}: below the Compose limit", span.widthPx < composeWidthLimit)
                    val local = span.leftPx - scroll
                    assertTrue("${s.name}: placement $local is viewport-local", local >= -2 * viewport - 1 && local <= 3 * viewport + 1)
                }
            }
        }
    }

    @Test fun oldFullWidthLayoutWouldExceedTheLimitButViewportDoesNot() {
        // The pre-Phase-5 layout used duration x zoom as a fixed width.
        assertTrue(timelinePx(20 * 60_000.0, 240f) > composeWidthLimit)    // 288,000 px: crashed
        assertTrue(timelinePx(12 * hour.toDouble(), 240f) > composeWidthLimit) // 10,368,000 px: crashed
        assertEquals(10_368_000.0, timelinePx(12 * hour.toDouble(), 240f), 0.0)
    }

    @Test fun distantClipCoordinatesAreExactAtTheEnd() {
        val total = 12 * hour
        val viewport = 339f
        val maxLeft = timelineMaxLeftMs(total, 240f, viewport)
        assertEquals(total - 339_000.0 / 240.0, maxLeft, 1e-9)
        val scroll = timelinePx(effectiveLeftMs(Double.MAX_VALUE, maxLeft), 240f)
        val window = timelineWindowPx(timelineWindowIndex(scroll, viewport), viewport)
        val outro = clipBoxSpan(total - 30_000L, 30_000L, 240f, window)
        assertNotNull(outro); outro!!
        // True clip origin is recovered exactly from the laid-out span.
        assertEquals(10_360_800.0, outro.leftPx - outro.hiddenLeftPx, 0.0)
        assertEquals(7_200.0, outro.fullWidthPx, 0.0)
        // The clip's real right edge sits exactly at the viewport's right edge at the arrangement end.
        assertEquals(viewport.toDouble(), outro.leftPx - outro.hiddenLeftPx + outro.fullWidthPx - scroll, 1e-6)
    }

    @Test fun distantClipCoordinatesAreExactInTheMiddle() {
        val total = 12 * hour
        val viewport = 339f
        val clipStart = total / 2 - 15_000L       // 21,585,000 ms
        val leftMs = clipStart - 500.0            // half a second before the clip
        val maxLeft = timelineMaxLeftMs(total, 240f, viewport)
        val scroll = timelinePx(effectiveLeftMs(leftMs, maxLeft), 240f)
        val window = timelineWindowPx(timelineWindowIndex(scroll, viewport), viewport)
        val span = clipBoxSpan(clipStart, 30_000L, 240f, window)!!
        assertEquals(0.0, span.hiddenLeftPx, 0.0)
        assertEquals(120, (span.leftPx - scroll).roundToInt()) // 0.5 s x 240 px/s, no drift at 6 hours
        // The ruler mark for the clip's start second lands on the same viewport x.
        assertEquals(120.0, timelinePx(clipStart.toDouble(), 240f) - scroll, 1e-6)
    }

    @Test fun fullLengthClipIsLaidOutOnlyForTheWindow() {
        val viewport = 339f
        val window = timelineWindowPx(timelineWindowIndex(5_000_000.0, viewport), viewport)
        val bed = clipBoxSpan(0L, 12 * hour, 240f, window)!!
        assertEquals(4 * 339, bed.widthPx)
        assertEquals(window.start, bed.leftPx, 0.0)
        assertEquals(window.start, bed.hiddenLeftPx, 0.0) // the clip starts at 0
        assertEquals(10_368_000.0, bed.fullWidthPx, 0.0)
    }

    @Test fun clipsOutsideTheWindowAreNotComposed() {
        val window = timelineWindowPx(10L, 339f)
        assertNull(clipBoxSpan(0L, 1_000L, 240f, window))
        assertNull(clipBoxSpan(Long.MAX_VALUE / 2, 1_000L, 240f, window))
        assertNull(clipBoxSpan(0L, 1_000L, 0f, window))
        assertNull(clipBoxSpan(0L, 1_000L, Float.NaN, window))
    }

    @Test fun shortClipsKeepTheMinimumWidth() {
        val span = clipBoxSpan(1_000L, 10L, 24f, timelineWindowPx(0L, 339f))!!
        assertEquals(MIN_CLIP_PX.toDouble(), span.fullWidthPx, 0.0)
        assertEquals(24, span.widthPx)
    }

    @Test fun dragEdgesAreTheClipsRealEdges() {
        val cut = ClipBoxSpan(leftPx = 5_000.0, widthPx = 1_356, hiddenLeftPx = 5_000.0, fullWidthPx = 20_000.0)
        assertEquals("touching the cut left side of a long clip moves it", 0, clipDragMode(0f, cut, 30f))
        val whole = ClipBoxSpan(leftPx = 100.0, widthPx = 400, hiddenLeftPx = 0.0, fullWidthPx = 400.0)
        assertEquals(1, clipDragMode(10f, whole, 30f))
        assertEquals(2, clipDragMode(390f, whole, 30f))
        assertEquals(0, clipDragMode(200f, whole, 30f))
        val tail = ClipBoxSpan(leftPx = 19_000.0, widthPx = 1_000, hiddenLeftPx = 19_000.0, fullWidthPx = 20_000.0)
        assertEquals("the real right edge of a cut clip still trims out", 2, clipDragMode(990f, tail, 30f))
    }

    @Test fun scrollConsumesExactlyWithoutDrift() {
        val maxLeft = timelineMaxLeftMs(12 * hour, 240f, 339f)
        val state = TimelineScrollState()
        repeat(10_000) { assertEquals(7.3f, state.scrollBy(7.3f, 240f, maxLeft), 0f) }
        // The way back may clamp at 0 on the very last step by a rounding residue, hence the small tolerance.
        repeat(10_000) { assertEquals(-7.3f, state.scrollBy(-7.3f, 240f, maxLeft), 1e-3f) }
        assertEquals(0.0, state.leftMs, 1e-6)
    }

    @Test fun scrollClampsAtBothEnds() {
        val maxLeft = timelineMaxLeftMs(3 * hour, 240f, 339f)
        assertEquals(TimelineScrollStep(0.0, 0f), timelineScrollStep(0.0, -50f, 240f, maxLeft))
        val nearEnd = timelineScrollStep(maxLeft - 100.0, 50f, 240f, maxLeft) // 100 ms = 24 px left
        assertEquals(maxLeft, nearEnd.leftMs, 0.0)
        assertEquals(24f, nearEnd.consumedPx, 1e-3f)
        assertEquals(TimelineScrollStep(maxLeft, 0f), timelineScrollStep(maxLeft, 10f, 240f, maxLeft))
    }

    @Test fun invalidInputsAreSafe() {
        assertEquals(TimelineScrollStep(0.0, 0f), timelineScrollStep(0.0, 10f, 0f, 100.0))
        assertEquals(TimelineScrollStep(0.0, 0f), timelineScrollStep(0.0, Float.NaN, 240f, 100.0))
        assertEquals(TimelineScrollStep(0.0, 0f), timelineScrollStep(Double.NaN, 0f, 240f, 100.0))
        assertEquals(0.0, timelineMaxLeftMs(0L, 240f, 339f), 0.0)
        assertEquals(0.0, timelineMaxLeftMs(-5L, 240f, 339f), 0.0)
        assertEquals(0.0, timelineMaxLeftMs(1_000L, Float.NaN, 339f), 0.0)
        assertEquals(0.0, timelinePx(1_000.0, Float.NaN), 0.0)
        assertEquals(0.0, timelinePx(Double.NaN, 240f), 0.0)
        assertEquals(0.0, renderedScrollPx(1_000.0, 2_000.0, Float.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, effectiveLeftMs(Double.POSITIVE_INFINITY, 1_000.0), 0.0)
        assertEquals(0.0, effectiveLeftMs(5.0, Double.NaN), 0.0)
        assertTrue(timelineMaxLeftMs(Long.MAX_VALUE, 240f, 339f).isFinite())
        val saturated = timelineWindowPx(timelineWindowIndex(1e30, 339f), 339f)
        // A saturated index exceeds Double's exact-integer range (2^53 px, ~1.2 million years at 240 px/s), so the
        // window may collapse to a point; what matters is that it never wraps negative or inverts.
        assertTrue("no Long overflow in the window", saturated.start > 0.0 && saturated.endInclusive >= saturated.start &&
            saturated.endInclusive.isFinite())
        assertEquals(0L, timelineWindowIndex(Double.NaN, 339f))
        assertTrue(visibleSecondsRange(0.0, 339f, 0f, 0f, 100L).isEmpty())
        assertEquals(TimelineScrollState(Double.NaN).leftMs, 0.0, 0.0)
        assertEquals(TimelineScrollState(-5.0).leftMs, 0.0, 0.0)
    }

    @Test fun zoomKeepsTheLeftEdgeTime() {
        val state = TimelineScrollState()
        var pxPerSec = 24f
        state.scrollBy(timelinePx(5_400_000.0, pxPerSec).toFloat(), pxPerSec, timelineMaxLeftMs(3 * hour, pxPerSec, 339f))
        val anchor = state.leftMs
        assertEquals(5_400_000.0, anchor, 1.0)
        // Repeated zoom in/out through the real zoom steps, scrolling a little at every zoom level.
        repeat(40) { i ->
            pxPerSec = if (i % 3 == 2) zoomedOut(pxPerSec) else zoomedIn(pxPerSec)
            val maxLeft = timelineMaxLeftMs(3 * hour, pxPerSec, 339f)
            val before = effectiveLeftMs(state.leftMs, maxLeft)
            // The rendered offset at the new zoom places the same moment at the left edge (to one pixel).
            assertEquals(timelinePx(before, pxPerSec), renderedScrollPx(state.leftMs, maxLeft, pxPerSec), 0.5)
            state.scrollBy(12f, pxPerSec, maxLeft)
            state.scrollBy(-12f, pxPerSec, maxLeft)
        }
        assertEquals("no drift after 40 zoom steps with scrolling", anchor, state.leftMs, 1e-6)
    }

    @Test fun shrinkingTheArrangementClampsTheStoredPosition() {
        val state = TimelineScrollState(3 * hour.toDouble())
        val shortMax = timelineMaxLeftMs(10 * 60_000L, 24f, 339f)
        state.clampTo(shortMax)
        assertEquals(shortMax, state.leftMs, 0.0)
        // Growing again does not jump back to the old 3-hour position.
        assertEquals(shortMax, effectiveLeftMs(state.leftMs, timelineMaxLeftMs(3 * hour, 24f, 339f)), 0.0)
    }

    @Test fun renderedScrollIsWholePixels() {
        val maxLeft = timelineMaxLeftMs(12 * hour, 240f, 339f)
        for (ms in listOf(0.0, 1.0, 2.1, 12_345.678, 21_584_500.0, maxLeft)) {
            val px = renderedScrollPx(ms, maxLeft, 240f)
            assertEquals(Math.rint(px), px, 0.0)
            assertTrue(abs(px - timelinePx(ms, 240f)) <= 0.5)
        }
    }

    @Test fun waveformDrawsOnlyBucketsInTheBox() {
        // 12 h clip, 400 buckets (25,920 px each at 240 px/s): a 1,356 px box shows at most 2 buckets.
        val far = visibleBucketRange(hiddenLeftPx = 5_000_000.0, boxWidthPx = 1_356f, fullWidthPx = 10_368_000.0, buckets = 400, padPx = 64f)
        assertEquals(192, far.first)
        assertEquals(193, far.last)
        // Short clip fully in the box: every bucket.
        assertEquals(0..399, visibleBucketRange(0.0, 7_200f, 7_200.0, 400, 64f))
        // Box past the clip's end clamps to the last bucket; nothing invalid.
        assertEquals(399..399, visibleBucketRange(9_000.0, 500f, 7_200.0, 400, 64f))
        assertTrue(visibleBucketRange(0.0, 100f, 7_200.0, 0, 64f).isEmpty())
        assertTrue(visibleBucketRange(0.0, 100f, 0.0, 400, 64f).isEmpty())
        assertTrue(visibleBucketRange(Double.NaN, 100f, 7_200.0, 400, 64f).isEmpty())
    }

    @Test fun zoomOutPastTheEndClampsToTheLastViewport() {
        val state = TimelineScrollState(3 * hour - 100.0)
        val maxAt6 = timelineMaxLeftMs(3 * hour, 6f, 339f)
        assertEquals(maxAt6, effectiveLeftMs(state.leftMs, maxAt6), 0.0)
        assertFalse(effectiveLeftMs(state.leftMs, maxAt6) > maxAt6)
    }

    @Test fun rulerDrawsOnlyVisibleSeconds() {
        val total = 12 * hour
        val viewport = 339f
        val lastSecond = total / 1000
        val atEnd = visibleSecondsRange(timelinePx(timelineMaxLeftMs(total, 240f, viewport), 240f), viewport, 40f, 240f, lastSecond)
        assertEquals(lastSecond, atEnd.last)
        assertTrue("bounded work: ${atEnd.count()} marks", atEnd.count() <= 4)
        val atStart = visibleSecondsRange(0.0, viewport, 40f, 6f, lastSecond)
        assertEquals(0L, atStart.first)
        assertTrue(atStart.count() <= (viewport + 80f) / 6f + 2)
        val mid = visibleSecondsRange(timelinePx(21_600_000.0, 24f), viewport, 0f, 24f, lastSecond)
        assertEquals(21_600L, mid.first)
    }

    @Test fun shortAndEmptyArrangementsDoNotScrollWhenTheyFit() {
        assertEquals(0.0, timelineMaxLeftMs(10_000L, 24f, 339f), 0.0)   // 240 px fits in 339 px
        val emptyMax = timelineMaxLeftMs(timelineDisplayDurationMs(emptyList()), 24f, 339f)
        assertEquals(30_000.0 - 339_000.0 / 24.0, emptyMax, 1e-9)       // 720 px display minimum, as before
    }
}
