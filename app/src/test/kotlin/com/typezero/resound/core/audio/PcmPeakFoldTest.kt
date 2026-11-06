package com.typezero.resound.core.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PcmPeakFoldTest {
    @Test fun real16BitPcmFoldsAcrossStereoFrames() {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(-16384).putShort(8192).putShort(4096).putShort(24576).flip()
        val mins = floatArrayOf(Float.POSITIVE_INFINITY)
        val maxs = floatArrayOf(Float.NEGATIVE_INFINITY)
        val frames = PcmPeakFold.foldBuffer(input, 2, AudioFormat.ENCODING_PCM_16BIT, 0, mins, maxs, { 0 }, {})
        val wave = WaveformDecode().complete(frames, mins, maxs, 100)
        assertEquals(2L, frames); assertEquals(WaveformProvenance.REAL, wave.provenance)
        assertEquals(-.5f, wave.mins[0], .00001f); assertEquals(.75f, wave.maxs[0], .00001f)
    }
    @Test fun realFloatPcmFoldsAndNormalizesEmptyBuckets() {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        input.putFloat(-.25f).putFloat(.5f).flip()
        val mins = FloatArray(2) { Float.POSITIVE_INFINITY }
        val maxs = FloatArray(2) { Float.NEGATIVE_INFINITY }
        val frames = PcmPeakFold.foldBuffer(input, 1, AudioFormat.ENCODING_PCM_FLOAT, 0, mins, maxs, { 0 }, {})
        val wave = WaveformDecode().complete(frames, mins, maxs, 100)
        assertEquals(2L, frames); assertEquals(-.25f, wave.mins[0], .00001f)
        assertEquals(.5f, wave.maxs[0], .00001f); assertEquals(0f, wave.maxs[1], 0f)
    }
    @Test fun foldingChecksCancellationWithinBuffer() {
        val input = ByteBuffer.allocate(20000)
        var checks = 0
        try {
            PcmPeakFold.foldBuffer(input, 1, AudioFormat.ENCODING_PCM_16BIT, 0,
                floatArrayOf(0f), floatArrayOf(0f), { 0 }) {
                checks++; if (checks == 2) throw CancellationException("cancel")
            }
            fail("no cooperative cancellation")
        } catch (_: CancellationException) { assertEquals(2, checks) }
    }
    @Test fun unsupportedPcmCannotBecomeRealWaveform() {
        try {
            PcmPeakFold.foldBuffer(ByteBuffer.allocate(8), 1, 999, 0, floatArrayOf(0f), floatArrayOf(0f), { 0 }, {})
            fail("unsupported PCM accepted")
        } catch (_: IllegalStateException) {}
    }
}
