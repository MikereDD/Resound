package com.typezero.resound.feature.timeline.draft

import java.io.*
import java.security.MessageDigest

/** Bounded versioned metadata; no waveform/audio bytes or Android dependencies. */
object ArrangementCodec {
    const val VERSION = 1
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAGIC = 0x52534446 // RSDF
    private const val MAX_TRACKS = 1000
    private const val MAX_CLIPS = 10000

    fun encode(draft: ArrangementDraft): ByteArray {
        validate(draft)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeLong(draft.revision); out.writeLong(draft.nextId)
            out.writeBoolean(draft.selectedClip != null)
            draft.selectedClip?.let(out::writeLong)
            out.writeFloat(draft.pxPerSec); out.writeInt(draft.tracks.size)
            for (track in draft.tracks) {
                out.writeLong(track.id); out.writeUTF(track.name)
                out.writeBoolean(track.muted); out.writeDouble(track.volume); out.writeInt(track.clips.size)
                for (clip in track.clips) {
                    out.writeLong(clip.id)
                    with(clip.source) {
                        out.writeUTF(uri); out.writeUTF(displayName); out.writeLong(durationMs)
                        out.writeInt(sampleRate); out.writeInt(channels); out.writeUTF(mimeType)
                    }
                    out.writeLong(clip.startMs); out.writeLong(clip.sourceInMs); out.writeLong(clip.sourceOutMs)
                }
            }
        }
        val payload = buffer.toByteArray()
        require(payload.size <= MAX_BYTES - 44) { "Arrangement metadata too large" }
        val result = ByteArrayOutputStream()
        DataOutputStream(result).use {
            it.writeInt(MAGIC); it.writeInt(VERSION); it.writeInt(payload.size)
            it.write(MessageDigest.getInstance("SHA-256").digest(payload)); it.write(payload)
        }
        return result.toByteArray()
    }

    fun decode(bytes: ByteArray): ArrangementDraft {
        require(bytes.size >= 8) { "Missing arrangement header" }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Not an arrangement draft" }
            val version = input.readInt()
            if (version != VERSION) throw UnsupportedDraftSchema(version)
            require(bytes.size in 44..MAX_BYTES) { "Invalid arrangement size" }
            val size = input.readInt()
            require(size == bytes.size - 44) { "Truncated or trailing arrangement bytes" }
            val expected = ByteArray(32).also(input::readFully)
            val payload = ByteArray(size).also(input::readFully)
            require(MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(payload))) { "Arrangement checksum mismatch" }
            DataInputStream(ByteArrayInputStream(payload)).use { p ->
                val revision = p.readLong(); val nextId = p.readLong()
                val selected = if (p.readBoolean()) p.readLong() else null
                val zoom = p.readFloat()
                var clipCount = 0
                val tracks = List(readCount(p, MAX_TRACKS)) {
                    val id = p.readLong(); val name = p.readUTF()
                    val muted = p.readBoolean(); val volume = p.readDouble()
                    val n = readCount(p, MAX_CLIPS)
                    clipCount += n; require(clipCount <= MAX_CLIPS) { "Too many clips" }
                    val clips = List(n) {
                        val cid = p.readLong()
                        val source = DraftSource(p.readUTF(), p.readUTF(), p.readLong(), p.readInt(), p.readInt(), p.readUTF())
                        DraftClip(cid, source, p.readLong(), p.readLong(), p.readLong())
                    }
                    DraftTrack(id, name, clips, muted, volume)
                }
                require(p.available() == 0) { "Unexpected payload bytes" }
                return ArrangementDraft(revision, tracks, nextId, selected, zoom).also(::validate)
            }
        }
    }

    private fun readCount(input: DataInputStream, max: Int): Int = input.readInt().also { require(it in 0..max) { "Invalid collection count" } }

    private fun validate(draft: ArrangementDraft) {
        require(draft.revision in 0 until Long.MAX_VALUE)
        require(draft.tracks.size <= MAX_TRACKS && draft.tracks.sumOf { it.clips.size } <= MAX_CLIPS)
        require(draft.pxPerSec.isFinite() && draft.pxPerSec in 6f..240f)
        val ids = HashSet<Long>(); val clipIds = HashSet<Long>()
        for (track in draft.tracks) {
            require(track.id > 0 && ids.add(track.id)) { "Duplicate or invalid track id" }
            require(track.volume.isFinite() && track.volume >= 0.0) { "Invalid track volume" }
            for (clip in track.clips) {
                require(clip.id > 0 && ids.add(clip.id)) { "Duplicate or invalid clip id" }
                clipIds.add(clip.id)
                require(clip.source.uri.isNotBlank() && clip.source.durationMs > 0)
                require(clip.source.sampleRate > 0 && clip.source.channels > 0)
                require(clip.sourceInMs >= 0 && clip.sourceInMs < clip.sourceOutMs && clip.sourceOutMs <= clip.source.durationMs)
                require(clip.startMs >= 0 && clip.startMs <= Long.MAX_VALUE - (clip.sourceOutMs - clip.sourceInMs))
            }
        }
        require(draft.nextId > (ids.maxOrNull() ?: 0L) && draft.nextId < Long.MAX_VALUE) { "Identifier reuse/overflow" }
        require(draft.selectedClip == null || draft.selectedClip in clipIds) { "Selection must reference a clip" }
    }
}
