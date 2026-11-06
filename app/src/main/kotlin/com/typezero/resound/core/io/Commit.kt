/*
 * file:    Commit.kt
 * desc:    Runs a short, bounded commit step (publish) off the UI thread and
 *          non-cancellably: once started it either finishes (committed) or fails
 *          (rolled back by the block itself); a navigation-triggered cancellation
 *          can no longer interrupt it half way.
 */
package com.typezero.resound.core.io

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

suspend fun <T> runCommitted(block: () -> T): T =
    withContext(Dispatchers.IO + NonCancellable) { block() }

/**
 * Thrown when audio WAS published but the local receipt could not be persisted. The draft is
 * retained. Retrying would duplicate the published copy; do not treat as ordinary failure.
 */
class PublishedButUntracked(val result: Any?, cause: Throwable) :
    Exception("Audio published; local completion tracking failed; draft retained", cause)

/** Publish, then persist a receipt; classify the "published but not tracked" outcome explicitly. */
internal fun <T> publishAndTrack(
    store: DraftStore,
    draft: java.io.File,
    receiptOf: (T) -> String,
    publish: () -> T,
): T {
    val result = publish()
    try {
        store.markPublished(draft, receiptOf(result))
    } catch (t: Throwable) {
        throw PublishedButUntracked(result, t)
    }
    return result
}

/**
 * Publish a draft recording to Music/Resound off the UI thread. The commit step is
 * non-cancellable but NOT time-bounded (provider IO can hang). Failure keeps the draft.
 */
suspend fun publishDraft(
    context: android.content.Context,
    store: DraftStore,
    draft: java.io.File,
    naming: NamingRequest? = null,
): Outputs.Published = runCommitted { publishDraftBlocking(context, store, draft, naming) }

/**
 * Explicit Save of an existing recovery draft. A stale recovery row (an earlier Save already
 * finished, possibly from a replaced screen) must not publish the same audio again: under the
 * caller's exclusive recovery lease, a verified existing publication returns null instead.
 */
suspend fun publishDraftIfUnpublished(
    context: android.content.Context,
    store: DraftStore,
    draft: java.io.File,
): Outputs.Published? = runCommitted {
    publishUnlessAlreadyPublished(store, draft) { publishDraftBlocking(context, store, draft) }
}

/** Null when [draft] already has a verified publication; otherwise exactly one [publish]. */
internal fun <T> publishUnlessAlreadyPublished(store: DraftStore, draft: java.io.File, publish: () -> T): T? =
    if (store.isPublished(draft)) null else publish()

private fun publishDraftBlocking(
    context: android.content.Context,
    store: DraftStore,
    draft: java.io.File,
    naming: NamingRequest? = null,
): Outputs.Published {
    val copy = Outputs.newTempFile(context, "recording", "m4a")
    try {
        draft.copyTo(copy, overwrite = true)
        return publishAndTrack(store, draft, { p: Outputs.Published -> p.uri.toString() }) {
            Outputs.publishNamedToMusic(context, copy, "recording", "m4a", naming)
        }
    } finally {
        copy.delete()
    }
}

/**
 * Create a file on [dispatcher] AND register it with [owner] before returning, so a cancellation
 * racing the withContext return cannot orphan it. A rejected registration deletes the fresh file.
 */
suspend fun allocateOwned(
    owner: OperationFiles,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    create: () -> java.io.File,
): java.io.File = withContext(dispatcher) { owner.adopt(create()) }

suspend fun allocateOwnedTemp(
    context: android.content.Context,
    baseName: String,
    ext: String,
    owner: OperationFiles,
): java.io.File = allocateOwned(owner) { Outputs.newTempFile(context, baseName, ext) }
