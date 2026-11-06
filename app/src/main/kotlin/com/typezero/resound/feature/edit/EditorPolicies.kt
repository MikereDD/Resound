/*
 * file:    EditorPolicies.kt
 * desc:    Small pure Editor decisions kept out of Compose so they can be host-tested:
 *          the Trim selection minimum and what to do with the microphone when the
 *          Editor leaves the foreground.
 */
package com.typezero.resound.feature.edit

import kotlinx.coroutines.ensureActive

/** Presentation only: an accepted source, not playback readiness, controls the editing workspace. */
internal fun showEditorWorkspace(hasSource: Boolean): Boolean = hasSource

/** Shortest Trim output Resound will encode and publish (equal endpoints produce no audio). */
internal const val MIN_TRIM_SELECTION_MS = 100L

/** A Trim range must lie inside the source and contain at least [MIN_TRIM_SELECTION_MS]. */
internal fun isTrimSelectionValid(startMs: Long, endMs: Long, durationMs: Long): Boolean =
    startMs >= 0L && endMs <= durationMs && endMs - startMs >= MIN_TRIM_SELECTION_MS

/** Closing only releases the displayed source; never audio/draft/reference ownership. */
internal fun canCloseEditorAudio(hasSource: Boolean, recording: Boolean, starting: Boolean,
                                 recorderBusy: Boolean, busy: Boolean): Boolean =
    hasSource && !recording && !starting && !recorderBusy && !busy

/** Existing retirement must acknowledge success before the caller clears its Editor state. */
internal suspend fun closeEditorAudio(admitted: Boolean, revoke: () -> Unit,
                                     awaitRetirement: suspend () -> Unit,
                                     clear: () -> Unit): Boolean {
    if (!admitted) return false
    revoke()
    awaitRetirement()
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    clear()
    return true
}

/** Library request identity is distinct from URI, so an explicit same-file selection is new work. */
internal data class EditorOpenRequest(val id: Long, val uri: String)

/** An old acknowledgement cannot consume a newer choice, even when both target the same URI. */
internal fun consumeEditorOpenRequest(pending: EditorOpenRequest?, acceptedId: Long): EditorOpenRequest? =
    pending?.takeUnless { it.id == acceptedId }

/** Acceptance means the existing load was admitted, not that decoding/playback succeeded. */
internal fun acceptEditorOpenRequest(request: EditorOpenRequest?, admit: (String) -> Boolean,
                                     acknowledge: (Long) -> Unit): Boolean {
    if (request == null || !admit(request.uri)) return false
    acknowledge(request.id)
    return true
}

/** Microphone policy when the Editor stops being visible (Home, lock, another app). */
internal enum class BackgroundRecordingAction { NONE, STOP_NOW, STOP_AFTER_START }

/**
 * Android silences or denies the microphone for apps without a visible activity or a
 * microphone foreground service, which Resound does not have. Stop and keep the recording
 * rather than capture silence. A start in flight is stopped as soon as it completes; a stop
 * (or the publication that follows it) already in flight needs nothing more.
 */
internal fun backgroundRecordingAction(
    recording: Boolean,
    starting: Boolean,
    recorderBusy: Boolean,
): BackgroundRecordingAction = when {
    starting -> BackgroundRecordingAction.STOP_AFTER_START
    recording && !recorderBusy -> BackgroundRecordingAction.STOP_NOW
    else -> BackgroundRecordingAction.NONE
}
