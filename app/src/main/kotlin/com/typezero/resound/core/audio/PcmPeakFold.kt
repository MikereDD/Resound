package com.typezero.resound.core.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure PCM folding; AudioFormat values are compile-time encoding constants. */
internal object PcmPeakFold {
    /** Fold one PCM output buffer into the bucket arrays. Returns the new frame counter. */
    fun foldBuffer(
        buf: ByteBuffer,
        channels: Int,
        pcmEncoding: Int,
        startFrame: Long,
        mins: FloatArray,
        maxs: FloatArray,
        bucketOf: (Long) -> Int,
        checkActive: () -> Unit,
    ): Long {
        var frame = startFrame
        val ch = channels.coerceAtLeast(1)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buf.asFloatBuffer()
            val n = fb.remaining()
            var i = 0
            while (i + ch <= n) {
                if (i % 4096 < ch) checkActive()

                var peak = 0f
                for (c in 0 until ch) {
                    val v = fb.get(i + c)
                    if (kotlin.math.abs(v) > kotlin.math.abs(peak)) peak = v
                }
                val b = bucketOf(frame)
                if (peak < mins[b]) mins[b] = peak
                if (peak > maxs[b]) maxs[b] = peak
                frame++
                i += ch
            }
        } else {

            check(pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) { "Unsupported PCM encoding" }
            // 16-bit PCM.
            val sb = buf.asShortBuffer()
            val n = sb.remaining()
            var i = 0
            while (i + ch <= n) {
                if (i % 4096 < ch) checkActive()

                var peak = 0f
                for (c in 0 until ch) {
                    val v = sb.get(i + c) / 32768f
                    if (kotlin.math.abs(v) > kotlin.math.abs(peak)) peak = v
                }
                val b = bucketOf(frame)
                if (peak < mins[b]) mins[b] = peak
                if (peak > maxs[b]) maxs[b] = peak
                frame++
                i += ch
            }
        }
        return frame
    }

}
