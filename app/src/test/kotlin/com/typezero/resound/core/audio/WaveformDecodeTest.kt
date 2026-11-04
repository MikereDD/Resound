package com.typezero.resound.core.audio

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WaveformDecodeTest {
    @Test fun cancelledDecoderCannotPublishLateResult() = runBlocking {
        var published = false
        val job = launch {
            WaveformDecode().extract({
                currentCoroutineContext().cancel()
                Waveform(floatArrayOf(0f), floatArrayOf(0f), 100)
            }) { error("must not fallback") }
            published = true
        }
        job.join()
        assertFalse("cancelled work republished a result", published)
    }
    @Test fun zeroFramesAreUnavailableNotRealSilence() = runBlocking {
        val decoder = WaveformDecode()
        val result = decoder.extract({ decoder.complete(0, floatArrayOf(0f), floatArrayOf(0f), 100) }) { Waveform.unavailable(100) }
        assertEquals(WaveformProvenance.UNAVAILABLE, result.provenance)
    }
    @Test fun actualSilentFramesRemainReal() {
        val result = WaveformDecode().complete(1, floatArrayOf(0f), floatArrayOf(0f), 100)
        assertEquals(WaveformProvenance.REAL, result.provenance)
    }
    @Test fun failedDecodeIsExplicitlyUnavailableNotSyntheticAudio() = runBlocking {
        val result = WaveformDecode().extract({ error("decode") }) { Waveform.unavailable(100) }
        assertEquals(WaveformProvenance.UNAVAILABLE, result.provenance)
        assertEquals(100L, result.durationMs)
        assertTrue(result.mins.all { it == 0f }); assertTrue(result.maxs.all { it == 0f })
    }
    @Test fun cancellationNeverSelectsUnavailable() = runBlocking {
        var fallback = false
        try {
            WaveformDecode().extract({ throw CancellationException("cancel") }) {
                fallback = true; Waveform(floatArrayOf(0f), floatArrayOf(0f), 100)
            }
            fail("cancellation suppressed")
        } catch (_: CancellationException) {}
        assertFalse(fallback)
    }
}
