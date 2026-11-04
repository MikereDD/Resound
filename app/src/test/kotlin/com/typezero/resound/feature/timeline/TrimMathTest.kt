package com.typezero.resound.feature.timeline

import org.junit.Assert.*
import org.junit.Test

class TrimMathTest {
    private val durations = listOf(1L, 100L, 199L, 200L, 201L, 5_000L)
    private val deltas = listOf(Long.MIN_VALUE / 4, -5_000L, -200L, -1L, 0L, 1L, 200L, 5_000L, Long.MAX_VALUE / 4)

    private fun check(s: ClipSpan, d: Long) {
        assertTrue("in>=0 $s", s.inMs >= 0)
        assertTrue("in<=out $s", s.inMs <= s.outMs)
        assertTrue("out<=D $s d=$d", s.outMs <= d)
        assertTrue("start>=0 $s", s.startMs >= 0)
        assertTrue("min length $s", s.outMs - s.inMs >= minOf(MIN_CLIP_MS, d))
    }

    @Test fun shortAndBoundaryDurationsNeverThrowAndKeepInvariants() {
        for (d in durations) for (delta in deltas) {
            val full = ClipSpan(0, 0, d)
            check(TrimMath.trimIn(full, delta, d), d)
            check(TrimMath.trimOut(full, delta, d), d)
        }
    }

    @Test fun unknownOrZeroDurationIsNoOp() {
        for (d in listOf(0L, -1L)) {
            val s = ClipSpan(10, 0, 0)
            assertEquals(s, TrimMath.trimIn(s, 50, d))
            assertEquals(s, TrimMath.trimOut(s, -50, d))
        }
    }

    @Test fun zeroMillisecondsBoundaryAtFlagDurations() {
        // Previously: durations < 200 made coerceIn(lo > hi) throw.
        for (d in listOf(1L, 100L, 199L)) {
            val r = TrimMath.trimIn(ClipSpan(0, 0, d), 50, d)
            assertEquals(0L, r.inMs)   // cannot shrink below the whole short clip
            assertEquals(d, r.outMs)
        }
        val r200 = TrimMath.trimIn(ClipSpan(0, 0, 200), 50, 200)
        assertEquals(0L, r200.inMs)
        val r201 = TrimMath.trimIn(ClipSpan(0, 0, 201), 50, 201)
        assertEquals(1L, r201.inMs)
    }

    @Test fun leftExtensionPreservesRightEdge() {
        // F14 example: start=100,in=1000,out=3000, delta -500 -> end must stay 2100.
        val before = ClipSpan(100, 1000, 3000)
        val after = TrimMath.trimIn(before, -500, 5000)
        assertEquals(before.startMs + (before.outMs - before.inMs), after.startMs + (after.outMs - after.inMs))
        assertEquals(0L, after.startMs)
        assertEquals(900L, after.inMs)
    }

    @Test fun rightEdgeInvariantAcrossRandomValidSpans() {
        val rnd = java.util.Random(42)
        repeat(2000) {
            val d = 1L + rnd.nextInt(10_000)
            val minLen = minOf(MIN_CLIP_MS, d)
            val i = rnd.nextInt((d - minLen + 1).toInt()).toLong()
            val o = i + minLen + rnd.nextInt((d - i - minLen + 1).toInt())
            val s = ClipSpan(rnd.nextInt(5000).toLong(), i, o) // valid precondition: length >= min
            val delta = rnd.nextInt(4001) - 2000L
            val r = TrimMath.trimIn(s, delta, d)
            check(r, d)
            assertEquals(s.startMs + (s.outMs - s.inMs), r.startMs + (r.outMs - r.inMs))
        }
    }

    @Test fun trimOutClampsToDuration() {
        assertEquals(1000L, TrimMath.trimOut(ClipSpan(0, 0, 900), 5000, 1000).outMs)
        assertEquals(200L, TrimMath.trimOut(ClipSpan(0, 0, 900), -5000, 1000).outMs)
    }
}
