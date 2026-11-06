package com.typezero.resound.core.audio

/** Register immediately after acquisition, before any configuring call. */
class DecodeResources {
    private val actions = mutableListOf<() -> Unit>()
    fun defer(action: () -> Unit) { actions.add(0, action) }
    fun <T> codec(create: () -> T, configure: (T) -> Unit, start: (T) -> Unit,
                  stop: (T) -> Unit, release: (T) -> Unit): T {
        val codec = create()
        defer { release(codec) }
        var started = false
        defer { if (started) stop(codec) }
        configure(codec)
        start(codec)
        started = true
        return codec
    }
    fun close(primary: Throwable?) {
        var failure = primary
        for (action in actions) {
            try { action() } catch (t: Throwable) {
                if (failure == null) failure = t else if (failure !== t) failure.addSuppressed(t)
            }
        }
        actions.clear()
        if (primary == null && failure != null) throw failure
    }
    inline fun <T> use(block: (DecodeResources) -> T): T {
        var primary: Throwable? = null
        try { return block(this) } catch (t: Throwable) { primary = t; throw t }
        finally { close(primary) }
    }
}

/** Monotonic cooperative budget; native calls themselves cannot be preempted. */
class DecodeBudget(private val now: () -> Long = System::nanoTime,
                   private val idleNanos: Long = 5_000_000_000L,
                   private val overallNanos: Long = 120_000_000_000L) {
    private val start = now()
    private var progressAt = start
    fun check(progress: Boolean = false) {
        val time = now()
        check(time - start < overallNanos) { "Waveform decode deadline exceeded" }
        check(time - progressAt < idleNanos) { "Waveform decoder made no progress" }
        if (progress) progressAt = time
    }
}
