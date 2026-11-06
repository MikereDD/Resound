package com.typezero.resound.feature.record

import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** Application-owned writer/action exclusion. All File arguments are canonicalized on IO first. */
class RecordingRecoveryAdmission {
    enum class State { STARTING, CAPTURING, STOPPING, FINALIZED, INDETERMINATE }
    data class Candidate internal constructor(
        val file: File,
        val generation: Long,
        internal val owner: RecordingRecoveryAdmission,
    )
    internal data class Writer(val file: File, val generation: Long)
    private data class Entry(
        var generation: Long = 0L,
        var state: State = State.FINALIZED,
        var action: Any? = null,
        var inspection: Boolean = false,
        var diagnostic: String? = null,
    )
    private val lock = Any()
    private val paths = mutableMapOf<File, Entry>()
    private var generation = 0L
    private val lifecycleRevision = MutableStateFlow(0L)
    // Inspection contention wakes admission waiters, never the UI listing that owns it.
    private val inspectionRevision = MutableStateFlow(0L)
    internal val lifecycleChanges = lifecycleRevision.asStateFlow()

    data class Protection(val file: File, val state: State, val diagnostic: String?)

    /** Only memory is copied under the monitor; callers filter roots on IO. */
    internal fun protections(): List<Protection> = synchronized(lock) {
        paths.mapNotNull { (file, entry) ->
            if (entry.state != State.FINALIZED || (entry.action != null && !entry.inspection))
                Protection(file, entry.state, entry.diagnostic) else null
        }
    }

    /** Publish outside the state monitor: collectors may perform IO. */
    internal fun notifyLifecycleChange() {
        lifecycleRevision.update { if (it == Long.MAX_VALUE) 0L else it + 1L }
    }

    internal fun beginWriter(file: File): Writer {
        val writer = synchronized(lock) {
            val entry = paths.getOrPut(file) { Entry() }
            check(entry.state == State.FINALIZED && entry.action == null) {
                "Recording draft is in use or its writer termination is uncertain"
            }
            check(generation < Long.MAX_VALUE) { "Recording generation exhausted" }
            generation++
            entry.generation = generation
            entry.state = State.STARTING
            entry.diagnostic = null
            Writer(file, generation)
        }
        notifyLifecycleChange()
        return writer
    }

    internal fun transition(writer: Writer, state: State, diagnostic: String? = null) {
        synchronized(lock) {
            val entry = paths[writer.file]
            check(entry != null && entry.generation == writer.generation) { "Obsolete recording writer" }
            entry.state = state
            entry.diagnostic = diagnostic
        }
        notifyLifecycleChange()
    }

    fun state(canonicalFile: File): State = synchronized(lock) {
        paths[canonicalFile]?.state ?: State.FINALIZED
    }

    fun diagnostic(canonicalFile: File): String? = synchronized(lock) { paths[canonicalFile]?.diagnostic }

    internal fun candidate(file: File): Candidate? = synchronized(lock) {
        val entry = paths[file]
        if (entry != null && (entry.state != State.FINALIZED || (entry.action != null && !entry.inspection))) null
        else Candidate(file, entry?.generation ?: 0L, this)
    }

    data class PreviewPreparationOutcome(
        val nativePreparationAttempted: Boolean,
        val preparationSucceeded: Boolean,
    )

    /** No monitor is held by a lease. Identity-specific closure cannot clear another action. */
    class Lease internal constructor(
        private val owner: RecordingRecoveryAdmission,
        private val file: File,
        private val identity: Any,
        private val inspection: Boolean,
    ) : AutoCloseable {
        private var retained = false // read/written only under owner's lock

        /**
         * Extracted Editor policy for source-bound host regressions, not a cleanup attestation.
         * The callback marks entry BEFORE native prepare can allocate/acquire its source.
         * A completed queue does not attest release of a failed native candidate. Keep the
         * lease when native preparation failed/aborted; rejection before native entry is safe.
         */
        suspend fun withPreviewPreparation(
            awaitRetirement: suspend () -> Unit,
            onUncertain: () -> Unit = {},
            prepare: suspend (onNativePreparationAttempted: () -> Unit) -> Boolean,
        ): PreviewPreparationOutcome = withContext(NonCancellable) {
            var nativePreparationAttempted = false
            var preparationSucceeded = false
            try {
                preparationSucceeded = prepare { nativePreparationAttempted = true }
            } finally {
                var uncertain: String? = null
                try { awaitRetirement() }
                catch (t: Throwable) {
                    uncertain = "Recovery preparation retirement uncertain: ${t.message}"
                }
                if (nativePreparationAttempted && !preparationSucceeded && uncertain == null) {
                    uncertain = "Native preparation failed or was aborted; candidate termination is unverified"
                }
                uncertain?.let {
                    retainIndeterminate(it)
                    onUncertain()
                }
            }
            PreviewPreparationOutcome(nativePreparationAttempted, preparationSucceeded)
        }

        fun retainIndeterminate(reason: String) {
            val changed = synchronized(owner.lock) {
                val entry = owner.paths[file]
                if (entry != null && entry.action === identity) {
                    val changed = !retained || entry.diagnostic != reason
                    retained = true
                    entry.diagnostic = reason
                    changed
                } else false
            }
            if (changed) owner.notifyLifecycleChange()
        }
        override fun close() {
            val closed = synchronized(owner.lock) {
                val entry = owner.paths[file]
                if (!retained && entry != null && entry.action === identity) {
                    entry.action = null
                    entry.inspection = false
                    true
                } else false
            }
            // Real action completion (including cancellation/failure) restores availability.
            // Listing's own inspection leases must never feed back into listing observation.
            if (closed) {
                if (inspection) owner.inspectionRevision.update { if (it == Long.MAX_VALUE) 0L else it + 1L }
                else owner.notifyLifecycleChange()
            }
        }
    }

    /** Nonblocking compatibility listing; real actions and observed listings use acquire. */
    internal fun tryInspection(candidate: Candidate): Lease? = tryAcquire(candidate, inspection = true)

    private fun tryAcquire(candidate: Candidate, inspection: Boolean): Lease? = synchronized(lock) {
        check(candidate.owner === this) { "Recovery candidate belongs to another owner" }
        val entry = paths.getOrPut(candidate.file) { Entry() }
        check(entry.generation == candidate.generation && entry.state == State.FINALIZED &&
            (entry.action == null || entry.inspection)) {
            "Draft unavailable: recording is active, changed, in use, or termination is uncertain. Refresh recovery."
        }
        if (entry.action != null) null else {
            val identity = Any()
            entry.action = identity
            entry.inspection = inspection
            Lease(this, candidate.file, identity, inspection)
        }
    }

    internal suspend fun acquire(candidate: Candidate, inspection: Boolean = false): Lease {
        while (true) {
            val observedInspection = inspectionRevision.value
            val lease = tryAcquire(candidate, inspection)
            if (lease != null) {
                if (!inspection) notifyLifecycleChange()
                return lease
            }
            // Suspend without holding a monitor or blocking a worker thread. On resumption,
            // recheck owner/generation/state; cancellation cannot allocate an action lease.
            inspectionRevision.first { it != observedInspection }
        }
    }
}
