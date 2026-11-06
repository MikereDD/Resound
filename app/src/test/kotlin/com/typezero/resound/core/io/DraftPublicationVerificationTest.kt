package com.typezero.resound.core.io

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.CancellationException

/** Real draft files; injected streams stand in only for the unavailable Android provider. */
class DraftPublicationVerificationTest {
    private val media = "content://media/external_primary/audio/media/71"
    private fun dir() = java.nio.file.Files.createTempDirectory("publication-check").toFile()
    private fun audio(d: File, bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        File(d, "recording_test.m4a").also { it.writeBytes(bytes) }
    private fun receipt(a: File, text: String = media) = File(a.path + ".saved").also { it.writeText(text) }

    @Test fun freshOwnerRetainsDraftForUnknownDeniedOrFailingProvider() {
        val readers: List<(URI) -> InputStream?> = listOf(
            { null }, { throw IOException("missing") }, { throw SecurityException("denied") },
            { throw IllegalStateException("provider unavailable") },
        )
        for (reader in readers) {
            val d = dir(); val a = audio(d); val r = receipt(a); val saved = r.readBytes()
            val cold = DraftStore(d, reader)
            assertEquals(0, cold.purgePublished())
            assertFalse(cold.isPublished(a)); assertEquals(listOf(a), cold.list())
            assertArrayEquals(byteArrayOf(1, 2, 3), a.readBytes()); assertArrayEquals(saved, r.readBytes())
        }
    }

    @Test fun verifiedMediaCopyHidesDuplicateWithoutDeletingAudioOrReceipt() {
        val d = dir(); val bytes = ByteArray(20000) { (it % 251).toByte() }
        val a = audio(d, bytes); val copy = File(dir(), "published.m4a").also { a.copyTo(it) }
        var closed = false
        val store = DraftStore(d) { reference ->
            assertEquals(media, reference.toString())
            object : FilterInputStream(copy.inputStream()) {
                override fun close() { closed = true; super.close() }
            }
        }
        store.markPublished(a, media)
        assertTrue(closed); closed = false
        assertTrue(store.isPublished(a)); assertTrue(closed)
        assertTrue(store.list().isEmpty()); assertEquals(0, store.purgePublished())
        assertArrayEquals(bytes, a.readBytes()); assertEquals(media, File(a.path + ".saved").readText())
    }

    @Test fun providerShortAndZeroLengthReadsStillVerifyIdenticalBytes() {
        val d = dir(); val a = audio(d, ByteArray(9000) { (it % 127).toByte() }); receipt(a)
        var closed = false
        val store = DraftStore(d) {
            object : FilterInputStream(a.inputStream()) { // fake provider copy, never a receipt file URI
                private var zero = true
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (zero) { zero = false; return 0 }
                    return super.read(b, off, minOf(len, 1))
                }
                override fun close() { closed = true; super.close() }
            }
        }
        assertTrue(store.isPublished(a)); assertTrue(closed); assertTrue(a.exists())
    }

    @Test fun mismatchedTruncatedEmptyAndExtendedCopiesStayRecoverable() {
        val d = dir(); val a = audio(d); val r = receipt(a)
        for (bytes in listOf(byteArrayOf(1, 2, 4), byteArrayOf(1, 2), byteArrayOf(), byteArrayOf(1, 2, 3, 4))) {
            val store = DraftStore(d) { ByteArrayInputStream(bytes) }
            assertFalse(store.isPublished(a)); assertEquals(listOf(a), store.list())
            assertTrue(a.exists()); assertEquals(media, r.readText())
        }
    }

    @Test fun verificationCancellationPropagatesWithoutChangingFiles() {
        val d = dir(); val a = audio(d); val r = receipt(a)
        val store = DraftStore(d) { throw CancellationException("cancelled") }
        try { store.isPublished(a); fail("cancellation must not be swallowed") }
        catch (_: CancellationException) { }
        assertTrue(a.exists()); assertEquals(media, r.readText())
    }

    @Test fun malformedAndUnsupportedReferencesNeverReachProvider() {
        val invalid = listOf("not a URI", "", " content://media/external_primary/audio/media/71",
            "https://example.invalid/audio", "content://other.provider/external/audio/media/71",
            "content://media/external_primary/audio/media/0", "content://media/external_primary/audio/media/999999999999999999999",
            "$media?query=1", "$media#fragment", "content:opaque", "file:relative",
            "file://localhost/tmp/recording.m4a", "content://media:1/external/audio/media/71")
        val d = dir(); val a = audio(d); var calls = 0
        val store = DraftStore(d) { calls++; ByteArrayInputStream(a.readBytes()) }
        for (reference in invalid) {
            receipt(a, reference)
            assertFalse(reference, store.isPublished(a)); assertTrue(a.exists())
        }
        assertEquals(0, calls)
    }

    @Test fun invalidUtf8ReceiptIsRetainedAndDraftRemainsVisible() {
        val d = dir(); val a = audio(d); val r = File(a.path + ".saved")
        val broken = byteArrayOf(0xc3.toByte(), 0x28)
        r.writeBytes(broken)
        assertFalse(DraftStore(d).isPublished(a)); assertEquals(listOf(a), DraftStore(d).list())
        assertArrayEquals(broken, r.readBytes()); assertTrue(a.exists())
    }

    @Test fun oversizedReceiptIsNotReadAsPublicationOrRewritten() {
        val d = dir(); val a = audio(d); val text = "content://media/" + "x".repeat(9000) + "/audio/media/71"
        val r = receipt(a, text); var calls = 0
        val store = DraftStore(d) { calls++; ByteArrayInputStream(a.readBytes()) }
        assertFalse(store.isPublished(a)); assertEquals(listOf(a), store.list())
        assertEquals(0, calls); assertEquals(text, r.readText())
    }

    @Test fun fileReceiptCannotUseItselfOrAnotherDraftAsPublishedCopy() {
        val d = dir(); val a = audio(d); val other = File(d, "recording_other.m4a").also { a.copyTo(it) }
        var calls = 0
        val store = DraftStore(d) { calls++; File(it).inputStream() }
        for (target in listOf(a, other)) {
            receipt(a, target.toURI().toString())
            assertFalse(store.isPublished(a)); assertTrue(a.exists()); assertTrue(other.exists())
        }
        assertEquals(0, calls)
    }

    @Test fun mediaReceiptWithoutProviderVerifierIsNotAssumedPublished() {
        val d = dir(); val a = audio(d); receipt(a)
        val cold = DraftStore(d)
        assertFalse(cold.isPublished(a)); assertEquals(listOf(a), cold.list()); assertTrue(a.exists())
    }

    @Test fun failedVerificationDoesNotReplaceExistingReceipt() {
        val d = dir(); val a = audio(d); val r = receipt(a, "legacy malformed reference")
        val store = DraftStore(d) { throw IOException("unverifiable") }
        try { store.markPublished(a, media); fail("verification failure must be explicit") }
        catch (_: IOException) { }
        assertEquals("legacy malformed reference", r.readText()); assertTrue(a.exists())
        assertFalse(File(r.path + ".tmp").exists())
    }

    @Test fun unverifiedPublishIsClassifiedAsPublishedButUntrackedAndDraftKept() {
        val d = dir(); val a = audio(d); val output = File(dir(), "published.m4a")
        val store = DraftStore(d) { throw IOException("provider cannot confirm") }
        try {
            publishAndTrack(store, a, { result: String -> result }) { a.copyTo(output); media }
            fail("publication uncertainty must not appear fully tracked")
        } catch (failure: PublishedButUntracked) {
            assertEquals(media, failure.result); assertTrue(failure.cause is IOException)
        }
        assertTrue(a.exists()); assertTrue(output.exists()); assertFalse(File(a.path + ".saved").exists())
        assertEquals(listOf(a), store.list())
    }

    @Test fun streamReadFailureClosesProviderAndKeepsRecoveryVisible() {
        val d = dir(); val a = audio(d); receipt(a); var closed = false
        val store = DraftStore(d) {
            object : InputStream() {
                override fun read(): Int = throw IOException("read failed")
                override fun close() { closed = true }
            }
        }
        assertFalse(store.isPublished(a)); assertTrue(closed)
        assertEquals(listOf(a), store.list()); assertTrue(a.exists())
    }

    @Test fun legacyFileReceiptWithUnicodeAndSpacesRemainsSupported() {
        val d = dir(); val a = audio(d); val output = File(dir(), "published café with spaces.m4a").also { a.copyTo(it) }
        val store = DraftStore(d); store.markPublished(a, output.toURI().toString())
        assertTrue(DraftStore(d).isPublished(a)); assertTrue(a.exists())
        assertTrue(output.delete()) // disposable host copy only
        assertEquals(listOf(a), DraftStore(d).list())
    }
}
