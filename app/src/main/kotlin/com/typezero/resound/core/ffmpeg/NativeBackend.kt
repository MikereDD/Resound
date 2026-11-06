/*
 * file:    NativeBackend.kt
 * desc:    Seam between the runner's ownership logic and the native engine, so the
 *          cancellation/ownership state machine is unit-testable without FFmpeg.
 */
package com.typezero.resound.core.ffmpeg

interface NativeBackend {
    /**
     * Submit async work. [onComplete] must be invoked exactly once when native work is
     * terminal. May throw synchronously if submission itself failed (assumed: no native
     * work exists then). Returns a handle for [cancel].
     */
    fun start(args: List<String>, onComplete: (FFmpegResult) -> Unit, onStats: (outMs: Long) -> Unit): Long

    /** Request cancellation; terminal state is still reported through onComplete. */
    fun cancel(handle: Long)
}
