package com.typezero.resound.core.io

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Process-wide, blocking IO-only gate for participating Resound publishers, not other apps. */
internal object PublicationGate {
    private val lock = ReentrantLock(true)
    fun <T> run(block: () -> T): T = lock.withLock(block)
    internal fun isQueued(thread: Thread): Boolean = lock.hasQueuedThread(thread)
}

/** The production pending-item lifecycle. Readback is deliberately outside rollback ownership. */
internal fun <I, M> publishPendingItem(
    insert: () -> I,
    write: (I) -> Unit,
    finalize: (I) -> Unit,
    readback: (I) -> M,
    cleanup: (I) -> Unit,
): Pair<I, M?> = PublicationGate.run {
    val item = insert()
    try {
        write(item)
        finalize(item)
    } catch (failure: Throwable) {
        try { cleanup(item) } catch (cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
        throw failure
    }
    // Published audio must survive an unavailable/denied metadata query.
    val metadata = try { readback(item) } catch (_: Exception) { null }
    item to metadata
}
