/*
 * file:    BackendFFmpegRunner.kt
 * desc:    Runs native work with explicit file ownership. Cancellation is observed BEFORE
 *          submission (handler installed before backend.start), retained in a CancelGate until a
 *          valid handle is published, then applied to exactly that handle. The owner is told when
 *          native work starts and when it is CONFIRMED terminal, independent of coroutine
 *          cancellation. A grace timeout never counts as termination.
 */
package com.typezero.resound.core.ffmpeg

import com.typezero.resound.core.io.OperationFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BackendFFmpegRunner(
    private val backend: NativeBackend,
    private val graceMs: Long = NATIVE_CANCEL_GRACE_MS,
    private val onGraceExpired: () -> Unit = {},
    private val onCancelObserved: () -> Unit = {},   // test hook: cancellation handler ran
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : FFmpegRunner {

    /**
     * The whole ownership/cancel state machine (handler installed before submission, CancelGate,
     * terminal reporting) runs on [dispatcher], so the blocking backend.start never runs on Main.
     * If the caller is cancelled while the result is returning, files stay owned until terminal.
     */
    override suspend fun run(
        args: List<String>,
        totalMs: Long,
        owner: OperationFiles?,
        progress: FFmpegProgress?,
    ): FFmpegResult {
        currentCoroutineContext().ensureActive()
        return withContext(dispatcher) { runOwned(args, totalMs, owner, progress) }
    }

    private suspend fun runOwned(
        args: List<String>,
        totalMs: Long,
        owner: OperationFiles?,
        progress: FFmpegProgress?,
    ): FFmpegResult {
        currentCoroutineContext().ensureActive()      // cancelled before submission: nothing starts
        val done = CompletableDeferred<FFmpegResult>()
        val tok = System.identityHashCode(done)
        com.typezero.resound.core.DevTrace.e("ffmpeg[$tok] submit")
        val gate = CancelGate()
        val accepting = AtomicBoolean(true)
        val terminalReported = AtomicBoolean(false)
        fun reportTerminal() {
            gate.terminal()
            if (terminalReported.compareAndSet(false, true)) owner?.nativeTerminal()
        }

        if (owner != null && !owner.nativeStarted()) {
            throw IllegalStateException("Operation files were already released; native work not started")
        }
        try {
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation {
                    accepting.set(false)
                    onCancelObserved()
                    com.typezero.resound.core.DevTrace.e("ffmpeg[$tok] cancel-observed")
                    gate.request()?.let { com.typezero.resound.core.DevTrace.e("ffmpeg[$tok] cancel-dispatch id=$it"); runCatching { backend.cancel(it) } }
                }
                val h = try {
                    backend.start(
                        args,
                        { result ->
                            com.typezero.resound.core.DevTrace.e("ffmpeg[$tok] terminal rc=${result.returnCode}")
                            reportTerminal()          // ownership first, then wake the caller
                            done.complete(result)
                            cont.resume(result)
                        },
                        { outMs ->
                            if (progress != null && accepting.get()) {
                                val ratio = if (totalMs > 0) (outMs.toFloat() / totalMs).coerceIn(0f, 1f) else null
                                progress.onProgress(ratio, outMs)
                            }
                        },
                    )
                } catch (t: Throwable) {
                    // Assumption: a synchronous submission exception means no native work exists.
                    reportTerminal()
                    done.completeExceptionally(t)
                    cont.resumeWithException(t)
                    return@suspendCancellableCoroutine
                }
                com.typezero.resound.core.DevTrace.e("ffmpeg[$tok] handle-published id=$h")
                gate.publish(h)?.let { com.typezero.resound.core.DevTrace.e("ffmpeg[$tok] cancel-dispatch(retained) id=$it"); runCatching { backend.cancel(it) } }
            }
        } catch (c: CancellationException) {
            if (!done.isCompleted) {
                val terminal = withContext(NonCancellable) { withTimeoutOrNull(graceMs) { done.join(); true } }
                if (terminal == null) { com.typezero.resound.core.DevTrace.e("ffmpeg[$tok] grace-expired"); onGraceExpired() }
            }
            throw c
        }
    }
}
