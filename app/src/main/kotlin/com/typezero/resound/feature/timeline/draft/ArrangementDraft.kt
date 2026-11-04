package com.typezero.resound.feature.timeline.draft

/** Metadata only. Never contains waveform arrays or audio bytes. List order is editing order. */
data class DraftSource(val uri: String, val displayName: String, val durationMs: Long,
    val sampleRate: Int, val channels: Int, val mimeType: String)
data class DraftClip(val id: Long, val source: DraftSource, val startMs: Long,
    val sourceInMs: Long, val sourceOutMs: Long)
data class DraftTrack(val id: Long, val name: String, val clips: List<DraftClip>,
    val muted: Boolean = false, val volume: Double = 1.0)
data class ArrangementDraft(val revision: Long = 0, val tracks: List<DraftTrack> = emptyList(),
    val nextId: Long = 1, val selectedClip: Long? = null, val pxPerSec: Float = 24f)

class UnsupportedDraftSchema(val version: Int) : java.io.IOException("Unsupported arrangement schema: $version")
