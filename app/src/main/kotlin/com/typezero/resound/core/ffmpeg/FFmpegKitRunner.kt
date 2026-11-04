/*
 * file:    FFmpegKitRunner.kt
 * version: 0.3.0
 * desc:    FFmpegKit adapter. All ownership/cancellation logic lives in BackendFFmpegRunner;
 *          this file only maps the wrapper API onto NativeBackend.
 */
package com.typezero.resound.core.ffmpeg

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit

class FFmpegKitBackend : NativeBackend {
    override fun start(
        args: List<String>,
        onComplete: (FFmpegResult) -> Unit,
        onStats: (Long) -> Unit,
    ): Long {
        val session = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { completed ->
                val rc = completed.returnCode
                onComplete(
                    FFmpegResult(
                        returnCode = if (rc != null) rc.value else -1,
                        logTail = completed.allLogsAsString?.takeLast(4000).orEmpty(),
                    )
                )
            },
            { /* log callback */ },
            { stats -> onStats(stats.time.toLong()) },
        )
        return session.sessionId
    }

    override fun cancel(handle: Long) = FFmpegKit.cancel(handle)
}

class FFmpegKitRunner(
    private val delegate: BackendFFmpegRunner = BackendFFmpegRunner(
        FFmpegKitBackend(),
        onGraceExpired = { Log.w("Resound", "FFmpeg did not confirm termination; operation files stay quarantined") },
    ),
) : FFmpegRunner by delegate
