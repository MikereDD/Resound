/*
 * file:    FFmpegRunner.kt
 * author:  Typezer∅
 * version: 0.1.0
 * desc:    Thin abstraction over an FFmpeg session. The whole "rich feature
 *          list" (cut, mix, fade, pitch, EQ, convert...) is built by handing
 *          this runner argument lists. Kept as an interface so the concrete
 *          FFmpeg AAR can be swapped without touching feature code.
 */
package com.typezero.resound.core.ffmpeg

import com.typezero.resound.core.io.OperationFiles

/** Result of a single FFmpeg invocation. */
data class FFmpegResult(
    val returnCode: Int,
    val logTail: String,
) {
    val isSuccess: Boolean get() = returnCode == 0
}

/** Progress callback. [ratio] is 0f..1f when total duration is known, else null. */
fun interface FFmpegProgress {
    fun onProgress(ratio: Float?, outTimeMs: Long)
}

interface FFmpegRunner {
    /**
     * Run FFmpeg with a pre-tokenised argument list (no shell parsing).
     * Suspends until the session completes.
     *
     * @param args     e.g. listOf("-y", "-i", inPath, "-ss", "0", "-to", "30", outPath)
     * @param totalMs  source duration in ms, used to compute [progress] ratio; pass 0 if unknown
     * @param owner    files used by native code; they are only deleted after confirmed native termination
     */
    suspend fun run(
        args: List<String>,
        totalMs: Long = 0L,
        owner: OperationFiles? = null,
        progress: FFmpegProgress? = null,
    ): FFmpegResult
}

/**
 * Placeholder until an FFmpeg AAR is wired in. Throws on use so a half-built
 * APK fails loudly instead of silently no-op'ing.
 *
 * Replace the body of [run] with a call into the chosen ffmpeg-kit fork:
 *   val session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(), { ... }, { log -> }, { stat -> })
 * and translate its callbacks into FFmpegResult / FFmpegProgress.
 */
class StubFFmpegRunner : FFmpegRunner {
    override suspend fun run(
        args: List<String>,
        totalMs: Long,
        owner: OperationFiles?,
        progress: FFmpegProgress?,
    ): FFmpegResult {
        throw NotImplementedError(
            "No FFmpeg backend wired in yet. See README → 'FFmpeg dependency' " +
                "and replace StubFFmpegRunner. args=$args"
        )
    }
}
