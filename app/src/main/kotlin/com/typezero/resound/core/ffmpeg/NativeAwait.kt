/*
 * file:    NativeAwait.kt
 * desc:    Cancellation of a native job is a REQUEST. This waits (bounded) for the
 *          native terminal callback before the cancellation exception propagates,
 *          so callers' finally-blocks only delete files after native code is done
 *          with them. On grace timeout it gives up waiting and reports it; the
 *          caller still cannot prove termination (documented residual risk).
 */
package com.typezero.resound.core.ffmpeg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val NATIVE_CANCEL_GRACE_MS = 15_000L

suspend fun <T> awaitNativeCompletion(
    done: CompletableDeferred<T>,
    requestCancel: () -> Unit,
    graceMs: Long = NATIVE_CANCEL_GRACE_MS,
    onGraceExpired: () -> Unit = {},
): T {
    try {
        return done.await()
    } catch (cancelled: CancellationException) {
        if (!done.isCompleted) {
            runCatching(requestCancel)
            val terminal = withContext(NonCancellable) {
                withTimeoutOrNull(graceMs) { done.join(); true }
            }
            if (terminal == null) onGraceExpired()
        }
        throw cancelled
    }
}
