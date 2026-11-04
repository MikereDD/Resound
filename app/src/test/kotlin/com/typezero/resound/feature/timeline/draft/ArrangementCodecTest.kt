package com.typezero.resound.feature.timeline.draft

import org.junit.Assert.*
import org.junit.Test

internal fun fixture(rev: Long = 1) = ArrangementDraft(rev, listOf(
    DraftTrack(7, "First", listOf(DraftClip(10,
        DraftSource("content://test/audio/one", "one.wav", 5000, 48000, 1, "audio/wav"),
        111, 199, 4201)), true, 0.625),
    DraftTrack(2, "Second", listOf(DraftClip(11,
        DraftSource("content://test/audio/two", "two.wav", 201, 44100, 2, "audio/wav"),
        9001, 1, 201)), false, 1.25)
), nextId = 12, selectedClip = 11, pxPerSec = 61.5f)

class ArrangementCodecTest {
    @Test fun shortFutureSchemaHeaderStillBlocksRatherThanBeingDiscardedAsCorruptV1() {
        val header = java.nio.ByteBuffer.allocate(8).putInt(0x52534446).putInt(2).array()
        try { ArrangementCodec.decode(header); fail() }
        catch (future: UnsupportedDraftSchema) { assertEquals(2, future.version) }
    }

    private fun rejected(bytes: ByteArray) {
        try { ArrangementCodec.decode(bytes); fail("must reject malformed data") } catch (_: Exception) { }
    }
    @Test fun emptyArrangementHasAVersionedRoundTrip() {
        val d = ArrangementDraft()
        assertEquals(d, ArrangementCodec.decode(ArrangementCodec.encode(d)))
    }
    @Test fun changedChecksumAndTruncationAreRejected() {
        val bytes = ArrangementCodec.encode(fixture())
        rejected(bytes.copyOf(bytes.size - 1))
        val changed = bytes.clone(); changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
        rejected(changed)
    }
    @Test fun futureSchemaIsNotTreatedAsEmpty() {
        val bytes = ArrangementCodec.encode(fixture())
        java.nio.ByteBuffer.wrap(bytes).putInt(4, 99)
        try { ArrangementCodec.decode(bytes); fail() }
        catch (e: UnsupportedDraftSchema) { assertEquals(99, e.version) }
    }
    @Test fun invalidBoundsDuplicateIdsAndNonFiniteGainAreRejectedBeforeWrite() {
        val d = fixture()
        val t = d.tracks.first()
        val c = t.clips.first()
        val invalid = listOf(
            d.copy(tracks = listOf(t.copy(clips = listOf(c.copy(sourceOutMs = c.source.durationMs + 1))))),
            d.copy(tracks = listOf(t, t)),
            d.copy(tracks = listOf(t.copy(volume = Double.NaN))),
            d.copy(nextId = 1),
            d.copy(selectedClip = 123),
            d.copy(pxPerSec = Float.NaN)
        )
        for (bad in invalid) try { ArrangementCodec.encode(bad); fail("bad draft accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun allShortClipDurationsPreserveExactMillisecondBoundsInMetadata() {
        for (ms in listOf(1L,100L,199L,200L,201L)) {
            val c = DraftClip(2, DraftSource("content://test/$ms", "short.wav", ms, 48000, 1, "audio/wav"), 17, 0, ms)
            val d = ArrangementDraft(1, listOf(DraftTrack(1, "short", listOf(c))), 3, 2)
            assertEquals(d, ArrangementCodec.decode(ArrangementCodec.encode(d)))
        }
    }

    @Test fun roundTripPreservesOrderedTracksReferencesExactTrimsGainMuteAndEditingState() {
        val wanted = fixture()
        assertEquals(wanted, ArrangementCodec.decode(ArrangementCodec.encode(wanted)))
    }
}
