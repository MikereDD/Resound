/*
 * file:    TimelineModel.kt
 * author:  Typezer∅
 * version: 0.5.0
 * desc:    Multitrack timeline model. A Timeline holds Tracks; each Track holds
 *          Clips positioned along a shared time axis. v0.5.0 clips span their
 *          full source (per-clip trimming within the timeline comes later).
 */
package com.typezero.resound.feature.timeline

import com.typezero.resound.core.audio.AudioFile
import com.typezero.resound.core.audio.Waveform

data class Clip(
    val id: Long,
    val source: AudioFile,
    val waveform: Waveform,
    val startMs: Long = 0L,
    val sourceInMs: Long = 0L,
    val sourceOutMs: Long = source.durationMs,
    /** Missing/revoked references stay as placeholders instead of losing user edits. */
    val sourceAvailable: Boolean = true,
) {
    val lengthMs: Long get() = (sourceOutMs - sourceInMs).coerceAtLeast(0L)
    val endMs: Long get() = startMs + lengthMs
}

data class Track(
    val id: Long,
    val name: String,
    val clips: List<Clip> = emptyList(),
    val muted: Boolean = false,
    val volume: Double = 1.0,
)

data class Timeline(
    val tracks: List<Track> = emptyList(),
) {
    /** Project length = the latest clip end across all tracks. */
    val durationMs: Long
        get() = tracks.flatMap { it.clips }.maxOfOrNull { it.endMs } ?: 0L
}
