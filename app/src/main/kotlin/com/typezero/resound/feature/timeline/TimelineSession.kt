/*
 * Application-owned active arrangement. Mutations and restore adoption run on the owner's Main
 * scope. Each property change queues a metadata snapshot; file IO/codec work happens on IO.
 * Automatic drafts are recovery checkpoints, not explicit project saves.
 */
package com.typezero.resound.feature.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.typezero.resound.feature.timeline.draft.*
import kotlinx.coroutines.*

class TimelineSession {
    private val trackState = mutableStateOf(emptyList<Track>())
    private val idState = mutableStateOf(1L)
    private val selectionState = mutableStateOf<Long?>(null)
    private val zoomState = mutableStateOf(24f)

    var tracks: List<Track>
        get() = trackState.value
        set(value) { if (trackState.value == value) return; trackState.value = value; changed() }
    var idCounter: Long
        get() = idState.value
        set(value) { if (idState.value == value) return; idState.value = value; changed() }
    var selectedClip: Long?
        get() = selectionState.value
        set(value) { if (selectionState.value == value) return; selectionState.value = value; changed() }
    var pxPerSec: Float
        get() = zoomState.value
        set(value) { if (zoomState.value == value) return; zoomState.value = value; changed() }

    var recoveryReady by mutableStateOf(true)
        private set
    var draftStatus by mutableStateOf("Automatic recovery draft — not an explicit project save")
        private set
    var persistenceError by mutableStateOf<String?>(null)
        private set

    private var writer: AsyncArrangementDrafts? = null
    private var started = false
    private var revision = 0L
    private var pendingRevision = 0L

    fun nextId(): Long {
        check(idCounter < Long.MAX_VALUE - 1) { "Identifier space exhausted" }
        return idCounter.also { idCounter = it + 1 }
    }

    private fun changed() {
        val owner = writer ?: return
        if (!recoveryReady) return
        check(revision < Long.MAX_VALUE - 1) { "Draft revision space exhausted" }
        revision++
        pendingRevision = revision
        draftStatus = "Automatic recovery draft: saving revision $revision…"
        if (!owner.offer(snapshotDraft(revision))) {
            persistenceError = "Automatic draft writer unavailable; current changes are not saved"
            draftStatus = "Automatic recovery draft: current changes not saved"
        }
    }

    internal fun snapshotDraft(revision: Long): ArrangementDraft {
        val draftTracks = tracks.map { t ->
            DraftTrack(t.id, t.name, t.clips.map { c ->
                val s = c.source
                DraftClip(c.id, DraftSource(s.uri.toString(), s.displayName, s.durationMs,
                    s.sampleRate, s.channels, s.mimeType), c.startMs, c.sourceInMs, c.sourceOutMs)
            }, t.muted, t.volume)
        }
        val validSelection = selectedClip?.takeIf { id -> tracks.any { t -> t.clips.any { it.id == id } } }
        return ArrangementDraft(revision, draftTracks, idCounter, validSelection, pxPerSec)
    }

    /** Called once by AppContainer, synchronously closes editing until load/adoption is complete. */
    internal fun startPersistence(
        scope: CoroutineScope,
        factory: () -> ArrangementStore,
        io: CoroutineDispatcher,
        hydrate: suspend (ArrangementDraft) -> List<Track>,
    ): Job {
        check(!started) { "Persistence owner already started" }
        started = true
        recoveryReady = false
        draftStatus = "Loading automatic recovery draft…"
        val owner = AsyncArrangementDrafts(scope, factory, io) { saved, failure ->
            if (failure != null && saved >= pendingRevision) {
                persistenceError = "Automatic draft not saved: ${failure.message}; no further automatic attempts. " +
                    "Current edits remain in memory; last valid draft retained"
                draftStatus = "Automatic recovery draft: current changes not saved"
            } else if (failure == null && saved >= pendingRevision) {
                persistenceError = null
                draftStatus = "Automatic recovery draft: committed revision $saved (not an explicit save)"
            }
        }
        writer = owner
        return scope.launch {
            try {
                val recovered = owner.load()
                if (recovered != null) {
                    // Suppress autosave during adoption so initial/default state cannot replace recovery.
                    tracks = hydrate(recovered)
                    idCounter = recovered.nextId
                    selectedClip = recovered.selectedClip
                    pxPerSec = recovered.pxPerSec
                    revision = recovered.revision
                    val missing = tracks.sumOf { t -> t.clips.count { !it.sourceAvailable } }
                    draftStatus = "Recovered automatic draft revision $revision" +
                        if (missing > 0) "; $missing unavailable audio reference(s)" else " (not an explicit save)"
                } else {
                    draftStatus = "Automatic recovery enabled (not an explicit project save)"
                }
                recoveryReady = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Keep original files and block editing/autosave, especially for newer schemas/corruption.
                persistenceError = "Recovery unavailable: ${failure.message}. Existing draft retained; editing disabled."
                draftStatus = "Automatic draft was not overwritten"
            }
        }
    }

    internal suspend fun finishPersistence() { writer?.finish() }
}
