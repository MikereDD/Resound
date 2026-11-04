/*
 * file:    RecordingController.kt
 * desc:    Single serialized owner of the recorder lifecycle. start/stop run on [io] (never the
 *          caller's thread), one at a time (Mutex), and are non-cancellable once begun so a
 *          navigating screen cannot abandon a half-started or half-stopped MediaRecorder.
 *          [scope] must outlive screens (application scope). Session tokens stop a late
 *          disposal-stop from ending a newer recording. Timings are recorded for measurement.
 */
package com.typezero.resound.feature.record

import kotlinx.coroutines.CancellationException
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

class RecordingController(
    private val recorder: Recorder,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    data class Timing(val op: String, val thread: String, val millis: Double)

    val recoveryAdmission = RecordingRecoveryAdmission()
    private val mutex = Mutex()
    private var writer: RecordingRecoveryAdmission.Writer? = null // mutex-owned, including uncertainty
    @Volatile var active: Long = 0L
        private set
    @Volatile var failure: String? = null
        private set
    val timings = CopyOnWriteArrayList<Timing>()

    private fun <T> timed(op: String, block: () -> T): T {
        val t0 = nanoTime()
        try { return block() } finally {
            val ms = (nanoTime() - t0) / 1e6
            timings.add(Timing(op, Thread.currentThread().name, ms))
            com.typezero.resound.core.DevTrace.e("recorder $op took=${ms}ms")
        }
    }

    /** Canonical registration precedes native start, even while public active is still zero. */
    suspend fun start(path: String): Long {
        var ticket: RecordingRecoveryAdmission.Writer? = null
        try {
            return mutex.withLock {
                withContext(io + NonCancellable) {
                    check(writer == null) { "Recorder is active or termination is uncertain" }
                    val owned = recoveryAdmission.beginWriter(File(path).canonicalFile)
                    ticket = owned
                    writer = owned
                    failure = null
                    try {
                        timed("start") { recorder.start(owned.file.path) }
                        recoveryAdmission.transition(owned, RecordingRecoveryAdmission.State.CAPTURING)
                        active = owned.generation
                        owned.generation
                    } catch (t: Throwable) {
                        val terminated = recorder.terminationAcknowledged
                        finish(owned, terminated, if (terminated) "Recording start failed; draft retained: ${t.message}"
                            else "Recording start failed; termination uncertain; draft retained and protected: ${t.message}")
                        throw t
                    }
                }
            }
        } catch (c: CancellationException) {
            // Prompt cancellation at the IO return retires THIS ticket, never a newer active.
            ticket?.let { stopDetached(it.generation) }
            throw c
        }
    }

    private fun finish(ticket: RecordingRecoveryAdmission.Writer, terminated: Boolean, diagnostic: String?) {
        recoveryAdmission.transition(ticket,
            if (terminated) RecordingRecoveryAdmission.State.FINALIZED else RecordingRecoveryAdmission.State.INDETERMINATE,
            diagnostic)
        failure = diagnostic
        active = 0L
        if (terminated) writer = null
    }

    /** A returned path requires both stopped audio AND explicit backend termination. */
    suspend fun stop(expected: Long? = null): String? = mutex.withLock {
        val ticket = writer ?: return@withLock null
        if ((expected != null && expected != ticket.generation) ||
            recoveryAdmission.state(ticket.file) != RecordingRecoveryAdmission.State.CAPTURING) return@withLock null
        withContext(io + NonCancellable) {
            recoveryAdmission.transition(ticket, RecordingRecoveryAdmission.State.STOPPING)
            val outcome = try {
                timed("stop") { recorder.stopOutcome() }
            } catch (t: Throwable) {
                finish(ticket, false, "Recording stop failed; termination uncertain: ${t.message}")
                throw t
            }
            val diagnostic = when {
                !outcome.terminated -> "Recorder termination uncertain; draft retained and protected: ${outcome.failure?.message ?: "no backend acknowledgement"}"
                outcome.failure != null -> "Recording stop failed; draft retained: ${outcome.failure.message}"
                else -> null
            }
            finish(ticket, outcome.terminated, diagnostic)
            if (outcome.terminated) outcome.path?.takeIf { File(it).canonicalFile == ticket.file } else null
        }
    }

    /** Stop in a scope that survives the calling screen; the file stays on disk as a draft. */
    fun stopDetached(token: Long): Job = scope.launch { runCatching { stop(token) } }

    /** Run small blocking cleanup (file IO) off the main thread in the surviving scope. */
    fun detached(block: () -> Unit): Job = scope.launch(io) { runCatching(block) }
}
