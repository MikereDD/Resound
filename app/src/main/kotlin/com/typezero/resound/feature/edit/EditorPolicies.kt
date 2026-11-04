/*
 * file:    EditorPolicies.kt
 * desc:    Small pure Editor decisions kept out of Compose so they can be host-tested:
 *          the Trim selection minimum and what to do with the microphone when the
 *          Editor leaves the foreground.
 */
package com.typezero.resound.feature.edit

/** Shortest Trim output Resound will encode and publish (equal endpoints produce no audio). */
internal const val MIN_TRIM_SELECTION_MS = 100L

/** A Trim range must lie inside the source and contain at least [MIN_TRIM_SELECTION_MS]. */
internal fun isTrimSelectionValid(startMs: Long, endMs: Long, durationMs: Long): Boolean =
    startMs >= 0L && endMs <= durationMs && endMs - startMs >= MIN_TRIM_SELECTION_MS

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
