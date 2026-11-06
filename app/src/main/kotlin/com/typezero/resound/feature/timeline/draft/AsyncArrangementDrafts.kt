package com.typezero.resound.feature.timeline.draft

import kotlinx.coroutines.*
import java.io.IOException

/** Application-scoped single writer; caller owns immutable snapshots. No disk/codec work on caller. */
class AsyncArrangementDrafts(
    scope: CoroutineScope,
    private val factory: () -> ArrangementStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onResult: (Long, Exception?) -> Unit = { _, _ -> },
) {
    private val store by lazy(factory) // resolved only inside IO blocks
    private val requests = kotlinx.coroutines.channels.Channel<ArrangementDraft>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var loaded = false
    private var highestOfferedRevision = Long.MIN_VALUE
    private val writer = scope.launch {
        for (first in requests) {
            var draft = first
            var retries = 0
            while (true) {
                try {
                    val committed = withContext(io) { store.save(draft) }
                    check(committed) { "Stale automatic draft revision rejected; another writer already committed" }
                    onResult(draft.revision, null)
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val newer = requests.tryReceive().getOrNull()
                    if (newer != null && newer.revision > draft.revision) {
                        draft = newer
                        retries = 0
                        continue
                    }
                    if (failure !is IOException || failure is CorruptArrangement || retries == RETRY_DELAYS_MS.size) {
                        onResult(draft.revision, failure)
                        break
                    }
                    delay(RETRY_DELAYS_MS[retries++])
                    val latest = requests.tryReceive().getOrNull()
                    if (latest != null && latest.revision > draft.revision) {
                        draft = latest
                        retries = 0
                    }
                }
            }
        }
    }

    /** Must succeed before edits can be offered; prevents initial blank state overwriting recovery. */
    suspend fun load(): ArrangementDraft? {
        check(!loaded) { "Arrangement already loaded" }
        return withContext(io) { store.load() }.also { loaded = true }
    }

    init {
        // A completed/cancelled writer must not leave a successful send into an orphaned channel.
        writer.invokeOnCompletion { requests.cancel() }
    }

    @Synchronized
    fun offer(draft: ArrangementDraft): Boolean {
        if (!loaded || !writer.isActive || draft.revision <= highestOfferedRevision) return false
        if (!requests.trySend(draft).isSuccess) return false
        highestOfferedRevision = draft.revision
        return true
    }

    /** Host tests / orderly shutdown only, not a synchronous UI flush. */
    suspend fun finish() { requests.close(); writer.join() }

    private companion object { val RETRY_DELAYS_MS = listOf(250L, 750L, 1500L) }
}
