package com.typezero.resound.core.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Duplicate-publication guard for explicit Save of an existing recovery draft (P4A-RP02/F18). */
class DraftPublishOnceTest {
    private val media = "content://media/external_primary/audio/media/42"
    private fun dir() = java.nio.file.Files.createTempDirectory("publish-once").toFile()

    @Test fun staleSaveOfAlreadyPublishedDraftDoesNotPublishAgain() {
        val d = dir()
        val bytes = ByteArray(4096) { (it % 199).toByte() }
        val draft = File(d, "recording_1.m4a").also { it.writeBytes(bytes) }
        val copy = File(dir(), "published.m4a").also { draft.copyTo(it) }
        val store = DraftStore(d) { reference -> if (reference.toString() == media) copy.inputStream() else null }
        store.markPublished(draft, media) // first Save completed (possibly on a replaced screen)

        var publishes = 0
        val result = publishUnlessAlreadyPublished(store, draft) { publishes++; "second" }

        assertNull(result)
        assertEquals(0, publishes)
        assertArrayEquals(bytes, draft.readBytes()) // draft is kept, never deleted by Save
    }

    @Test fun unpublishedDraftIsPublishedExactlyOnce() {
        val d = dir()
        val draft = File(d, "recording_2.m4a").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val store = DraftStore(d) { null }

        var publishes = 0
        val result = publishUnlessAlreadyPublished(store, draft) { publishes++; "first" }

        assertEquals("first", result)
        assertEquals(1, publishes)
    }

    @Test fun unverifiableReceiptStillAllowsSaveSoAudioIsNeverStranded() {
        val d = dir()
        val draft = File(d, "recording_3.m4a").also { it.writeBytes(byteArrayOf(9, 9, 9)) }
        File(draft.path + ".saved").writeText(media) // provider copy missing or denied
        val store = DraftStore(d) { null }

        var publishes = 0
        publishUnlessAlreadyPublished(store, draft) { publishes++ }

        assertEquals(1, publishes)
        assertTrue(draft.isFile)
    }
}
