/*
 * file:    WaveformExtractor.kt
 * author:  Mike Redd (typezero)
 * version: 0.1.0
 * desc:    Decodes an audio source to a downsampled peak array for rendering.
 *          One bucket = (min, max) sample amplitude over a window, so the
 *          Canvas can draw the familiar filled waveform cheaply. Decode once,
 *          cache the Waveform, reuse for zoom/scroll/scrub.
 *
 *          Implementation note: use MediaExtractor + MediaCodec to PCM, then
 *          bucket. This stub defines the shape and a fake generator so the UI
 *          can be built before the decode path is finished.
 */
package com.typezero.resound.core.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin

/**
 * Downsampled waveform. [mins] and [maxs] are parallel arrays, one entry per
 * bucket, each in -1f..1f. [bucketCount] = mins.size.
 */
enum class WaveformProvenance { REAL, SYNTHETIC, UNAVAILABLE }

class Waveform(
    val mins: FloatArray,
    val maxs: FloatArray,
    val durationMs: Long,
    val provenance: WaveformProvenance = WaveformProvenance.REAL,
) {
    val bucketCount: Int get() = mins.size
    companion object {
        fun unavailable(durationMs: Long) = Waveform(floatArrayOf(0f), floatArrayOf(0f), durationMs, WaveformProvenance.UNAVAILABLE)
    }
}

interface WaveformExtractor {
    /**
     * @param targetBuckets desired horizontal resolution (e.g. screen width in dp,
     *                      or wider to allow zoom). Real impl picks the PCM window
     *                      size from totalSamples / targetBuckets.
     */
    suspend fun extract(file: AudioFile, targetBuckets: Int = 2000): Waveform
}

/**
 * Placeholder that synthesises a plausible-looking waveform so the editor
 * screen renders during development. Swap for a MediaCodec-backed impl.
 */
class FakeWaveformExtractor : WaveformExtractor {
    override suspend fun extract(file: AudioFile, targetBuckets: Int): Waveform =
        withContext(Dispatchers.Default) {
            val n = targetBuckets.coerceAtLeast(1)
            val mins = FloatArray(n)
            val maxs = FloatArray(n)
            for (i in 0 until n) {
                val t = i.toFloat() / n
                // A couple of overlaid envelopes so it doesn't look uniform.
                val env = (0.4f + 0.6f * sin(t * 6.283f * 3f) * sin(t * 6.283f * 0.5f))
                val a = (0.15f + 0.8f * kotlin.math.abs(env)).coerceIn(0f, 1f)
                maxs[i] = a
                mins[i] = -a
            }
            Waveform(mins, maxs, file.durationMs, WaveformProvenance.SYNTHETIC)
        }
}
