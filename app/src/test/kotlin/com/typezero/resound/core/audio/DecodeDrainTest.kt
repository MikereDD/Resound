package com.typezero.resound.core.audio

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DecodeDrainTest {
    @Test fun codecThatNeverReturnsEosStopsAtIdleBudget() = runBlocking {
        var now = 0L; var steps = 0
        val drain = DecodeDrain(DecodeBudget({ now }, 5, 100))
        try {
            drain.run { steps++; now++; check(steps < 20) { "test safety bound, not decoder budget" }; DecodeStep(false, false) }
            fail("expected timeout")
        } catch (t: IllegalStateException) { assertEquals("Waveform decoder made no progress", t.message) }
        assertTrue(steps <= 5)
    }
    @Test fun endlessProgressCannotEvadeOverallBudget() = runBlocking {
        var now = 0L; var steps = 0
        val drain = DecodeDrain(DecodeBudget({ now }, 5, 10))
        try {
            drain.run { steps++; now++; check(steps < 20) { "test safety bound" }; DecodeStep(true, false) }
            fail("expected timeout")
        } catch (t: IllegalStateException) { assertEquals("Waveform decode deadline exceeded", t.message) }
        assertTrue(steps <= 10)
    }
    @Test fun cancellationInsideStepCannotDrainAgain() = runBlocking {
        var steps = 0
        val job = launch {
            val context = currentCoroutineContext()
            DecodeDrain(DecodeBudget()).run {
                steps++
                context.cancel()
                check(steps < 3) { "test safety bound" }
                DecodeStep(false, false)
            }
        }
        job.join()
        assertEquals(1, steps)
    }

}
