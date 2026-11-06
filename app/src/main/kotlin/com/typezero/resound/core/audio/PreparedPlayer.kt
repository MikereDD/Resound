package com.typezero.resound.core.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Platform-free ownership seam used by AudioPlayer. */
interface PlayerBackend {
    fun prepare()
    fun start()
    fun pause()
    fun seekTo(ms: Long)
    fun release()
    val isPlaying: Boolean
    val healthy: Boolean get() = true
    fun onFailure(handler: () -> Unit) {}
    val currentMs: Long
}

class PreparedPlayer(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    retirementBudgetMs: Long = 5_000,
    private val awaitPolicy: suspend (Deferred<Unit>) -> Unit = { completion ->
        check(withTimeoutOrNull(retirementBudgetMs) { completion.await(); true } == true) {
            "Player retirement timed out; playback unavailable"
        }
    },
) {
    private data class Retirement(val player: PlayerBackend, val ticket: Long, val installed: Boolean,
                                  val completion: CompletableDeferred<Unit>)
    private var preparing = 0
    private var retirementFault = false
    private val retirements = Channel<Retirement>(Channel.UNLIMITED)
    private var tail: Deferred<Unit> = CompletableDeferred(Unit)
    // Owned until close AND all native prepare producers exit. Never cancelled by a load.
    private val lifetime = Job()
    private val worker = CoroutineScope(lifetime + Dispatchers.IO).launch {
        try {
            for (item in retirements) {
                val failed = cleanup(item.player)
                synchronized(this@PreparedPlayer) {
                    if (failed && item.installed) retirementFault = true
                    if (failed && generation == item.ticket) failure = "Player cleanup failed"
                }
                item.completion.complete(Unit)
            }
        } finally { lifetime.complete() }
    }
    private fun enqueue(p: PlayerBackend, installed: Boolean, ticket: Long = generation) {
        val completion = CompletableDeferred<Unit>()
        check(retirements.trySend(Retirement(p, ticket, installed, completion)).isSuccess)
        tail = completion
    }
    /** Cancellable, bounded caller wait; timeout never cancels the owned native worker. */
    suspend fun awaitRetirement() {
        val completion = synchronized(this) { tail }
        awaitPolicy(completion)
        synchronized(this) { check(!retirementFault) { "Player cleanup failed" } }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
    }
    private fun closeQueueIfFinished() { if (closed && preparing == 0) retirements.close() }
    suspend fun awaitClosed() { worker.join() }
    private var backend: PlayerBackend? = null
    private var source: String? = null
    private var closed = false
    /** Observation only; native error callbacks own revocation, never composition. */
    @Synchronized fun readyFor(key: String): Boolean {
        if (closed || source != key || backend == null) return false
        return backend!!.healthy
    }
    private var generation = 0L
    @Volatile var failure: String? = null
        private set
    private val ownerIdentity = Any()
    internal fun owns(ticket: PreparationTicket): Boolean = ticket.ownerIdentity === ownerIdentity
    @Synchronized internal fun isCurrent(ticket: PreparationTicket): Boolean =
        owns(ticket) && !closed && generation == ticket.generation
    suspend fun prepare(key: String, create: () -> PlayerBackend) = prepareBound(key) { create() }
    suspend fun prepareBound(key: String, create: (PreparationTicket) -> PlayerBackend) {
        val context = synchronized(this) {
            check(!closed); invalidate(); preparing++
            PreparationTicket(key, generation, ownerIdentity)
        }
        val ticket = context.generation
        // Outer ownership survives prompt cancellation at the dispatcher return boundary.
        var candidate: PlayerBackend? = null
        var candidateFailed = false // guarded by this owner, including the installation boundary
        try {
            awaitRetirement()
            withContext(dispatcher) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                synchronized(this@PreparedPlayer) {
                    if (closed || generation != ticket) throw CancellationException("Obsolete preparation")
                }
                candidate = create(context)
                val p = candidate!!
                p.onFailure {
                    synchronized(this@PreparedPlayer) { candidateFailed = true; backendFailed(p) }
                }
                candidate!!.prepare()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
            }
            synchronized(this) {
                if (closed || generation != ticket) throw kotlinx.coroutines.CancellationException("Obsolete preparation")
                check(!candidateFailed && candidate!!.healthy) { "Playback unavailable" }
                backend = candidate; source = key; candidate = null; failure = null
            }
        } catch (t: Throwable) {
            synchronized(this) { if (generation == ticket) failure = "Playback unavailable" }
            throw t
        } finally {
            synchronized(this) {
                candidate?.let { enqueue(it, false, ticket) }
                preparing--
                closeQueueIfFinished()
            }
        }
    }
    private fun cleanup(p: PlayerBackend): Boolean {
        val diagnostic = IllegalStateException("Player cleanup failed")
        val resources = DecodeResources()
        // release is valid for prepared, playing and failed candidates; pause is not.
        // Retirement completes only after release returns, before new-source adoption.
        resources.defer { p.release() }
        resources.close(diagnostic)
        return diagnostic.suppressed.isNotEmpty()
    }
    /** Synchronous revocation and queue transfer only; no native cleanup under this monitor. */
    @Synchronized fun invalidate() {
        generation++; source = null
        val p = backend; backend = null
        if (p != null) enqueue(p, true)
    }
    @Synchronized private fun transport(key: String, action: (PlayerBackend) -> Unit) {
        check(readyFor(key)) { "Playback unavailable" }
        try { action(backend!!) } catch (c: CancellationException) { throw c }
        catch (t: Exception) { invalidate(); failure = "Playback unavailable"; throw t }
    }
    @Synchronized private fun backendFailed(p: PlayerBackend) {
        if (backend === p) { invalidate(); failure = "Playback unavailable" }
    }
    fun play(key: String, ms: Long?) = transport(key) { if (ms != null) it.seekTo(ms); it.start() }
    fun seekTo(key: String, ms: Long) = transport(key) { it.seekTo(ms) }
    @Synchronized fun pause() { val key = source ?: return; transport(key) { it.pause() } }
    val isPlaying: Boolean @Synchronized get() {
        val key = source ?: return false
        var value = false; transport(key) { value = it.isPlaying }; return value
    }
    val currentMs: Long @Synchronized get() {
        val key = source ?: return 0L
        var value = 0L; transport(key) { value = it.currentMs }; return value
    }
    @Synchronized fun release() { closed = true; invalidate(); closeQueueIfFinished() }
}
