/*
 * file:    CancelGate.kt
 * desc:    Per-session cancellation state shared by the cancel path, handle publication and
 *          terminal callback. "No handle yet" is null, never a placeholder id. A cancel that
 *          arrives first is retained and released to the caller exactly once when a valid handle
 *          is published; nothing is dispatched after the session is terminal. The returned id is
 *          to be used OUTSIDE any lock by the caller.
 */
package com.typezero.resound.core.ffmpeg

class CancelGate {
    private val lock = Any()
    private var requested = false
    private var handle: Long? = null
    private var dispatched = false
    private var terminal = false

    /** Cancellation requested. Returns the handle to cancel now, or null (not yet published / done / already sent). */
    fun request(): Long? = synchronized(lock) {
        requested = true
        takeLocked()
    }

    /** A valid session handle exists. Returns it if a retained cancel must be dispatched now. */
    fun publish(h: Long): Long? = synchronized(lock) {
        handle = h
        takeLocked()
    }

    fun terminal() { synchronized(lock) { terminal = true } }

    private fun takeLocked(): Long? {
        val h = handle
        if (requested && h != null && !dispatched && !terminal) { dispatched = true; return h }
        return null
    }
}
