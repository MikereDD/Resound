package com.typezero.resound.core.io

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class DraftStoreTest {
    private fun dir() = java.nio.file.Files.createTempDirectory("drafts").toFile()
    private fun draft(s: DraftStore, bytes: ByteArray = byteArrayOf(1, 2, 3)) = s.newFile("recording", "m4a").also { it.writeBytes(bytes) }

    @Test fun coldLaunchWithMalformedReceiptKeepsOnlyCopyRecoverable() {
        val d = dir(); val a = draft(DraftStore(d), byteArrayOf(9, 8, 7))
        val receipt = File(a.path + ".saved").also { it.writeText("not a publication URI") }
        val cold = DraftStore(d) // fresh Editor owner, no current recording exclusion
        assertEquals(0, cold.purgePublished())
        assertArrayEquals(byteArrayOf(9, 8, 7), a.readBytes())
        assertEquals("not a publication URI", receipt.readText())
        assertEquals(listOf(a), cold.list())
    }

    @Test fun coldLaunchWithMissingDestinationKeepsDraftVisible() {
        val d = dir(); val a = draft(DraftStore(d), byteArrayOf(4, 5, 6))
        val missing = File(dir(), "never-published.m4a")
        val receipt = File(a.path + ".saved").also { it.writeText(missing.toURI().toString()) }
        val cold = DraftStore(d)
        assertEquals(0, cold.purgePublished())
        assertTrue(a.exists()); assertTrue(receipt.exists())
        assertFalse(cold.isPublished(a)); assertEquals(listOf(a), cold.list())
    }

    @Test fun coldLaunchNeverPurgesEvenVerifiedPublication() {
        val d = dir(); val a = draft(DraftStore(d), byteArrayOf(6, 7, 8))
        val output = File(dir(), "published.m4a").also { a.copyTo(it) }
        DraftStore(d).markPublished(a, output.toURI().toString())
        val receipt = File(a.path + ".saved"); val originalReceipt = receipt.readBytes()
        val cold = DraftStore(d)
        assertTrue(cold.isPublished(a))
        assertEquals(0, cold.purgePublished())
        assertArrayEquals(byteArrayOf(6, 7, 8), a.readBytes())
        assertArrayEquals(originalReceipt, receipt.readBytes())
        assertTrue(cold.list().isEmpty()) // a verified duplicate need not be offered as unsaved
        assertTrue(output.delete()) // disposable external copy only
        assertEquals(listOf(a), DraftStore(d).list()) // later loss of the publication is recoverable
    }

    @Test fun markPublishedRejectsUnverifiableDestinationWithoutCreatingReceipt() {
        val d = dir(); val s = DraftStore(d); val a = draft(s)
        val missing = File(dir(), "missing-output.m4a").toURI().toString()
        try { s.markPublished(a, missing); fail("unverified publication must be explicit") }
        catch (_: IOException) { }
        assertFalse(File(a.path + ".saved").exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), a.readBytes())
        assertEquals(listOf(a), s.list())
    }

    @Test fun markPublishedRejectsReferenceBeyondReceiptLimit() {
        val d = dir()
        val store = DraftStore(d) { java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        val a = draft(store)
        val oversized = "content://media/" + "x".repeat(9000) + "/audio/media/1"
        try { store.markPublished(a, oversized); fail("receipt limits must also apply before write") }
        catch (_: IOException) { }
        assertFalse(File(a.path + ".saved").exists()); assertTrue(a.exists())
    }

    @Test fun restartDiscoversUnpublishedWithBytesIntact() {
        val d = dir(); val a = draft(DraftStore(d), byteArrayOf(9, 8, 7))
        DraftStore(d).newFile("recording", "m4a")            // zero-byte failed start
        val after = DraftStore(d)                             // "new process"
        assertEquals(listOf(a), after.list()); assertArrayEquals(byteArrayOf(9, 8, 7), a.readBytes())
    }
    @Test fun verifiedPublicationHidesDuplicateWithoutPurgingOriginal() {
        val d = dir(); val s = DraftStore(d); val a = draft(s); val b = draft(s)
        val published = File(dir(), "published.m4a").also { a.copyTo(it) }
        s.markPublished(a, published.toURI().toString())
        assertTrue(DraftStore(d).isPublished(a)); assertEquals(listOf(b), DraftStore(d).list())
        assertEquals(0, s.purgePublished()); assertTrue(a.exists()); assertTrue(b.exists())
        assertTrue(File(a.path + ".saved").exists())
    }
    @Test fun corruptOrEmptyReceiptMeansNotPublished() {
        val d = dir(); val s = DraftStore(d); val a = draft(s)
        File(a.path + ".saved").writeText("")
        assertFalse(s.isPublished(a)); assertEquals(listOf(a), s.list()); assertEquals(0, s.purgePublished()); assertTrue(a.exists())
    }
    @Test fun blankReceiptRejected() {
        val s = DraftStore(dir()); val a = draft(s)
        try { s.markPublished(a, "  "); fail() } catch (_: IllegalArgumentException) { }
        assertFalse(s.isPublished(a))
    }
    @Test fun receiptWriteFailureIsExplicitAndDraftKept() {
        val s = DraftStore(dir()); val a = draft(s)
        File(a.path + ".saved").mkdir()                       // directory in the way: move cannot replace it
        File(a.path + ".saved", "x").writeText("x")           // non-empty dir -> move fails
        val output = File(dir(), "published.m4a").also { a.copyTo(it) }
        try { s.markPublished(a, output.toURI().toString()); fail("expected IOException") } catch (_: IOException) { }
        assertTrue(a.exists()); assertFalse(s.isPublished(a))
    }
    @Test fun inUseDraftNotPurged() {
        val s = DraftStore(dir()); val a = draft(s)
        val output = File(dir(), "published.m4a").also { a.copyTo(it) }
        s.markPublished(a, output.toURI().toString())
        assertEquals(0, s.purgePublished(setOf(a))); assertTrue(a.exists())
    }
    @Test fun failedAudioDeleteKeepsReceipt() {
        val d = dir(); val s = DraftStore(d)
        val a = File(d, "recording_x.m4a"); a.mkdirs()          // a directory: not a draft file, never touched
        File(a.path + ".saved").writeText("uri")
        assertEquals(0, s.purgePublished()); assertTrue(File(a.path + ".saved").exists())
    }
    @Test fun orphanReceiptAndUnknownFilesAreNotSwept() {
        val d = dir(); val s = DraftStore(d)
        val orphan = File(d, "gone.m4a.saved").also { it.writeText("uri") }
        val odd = File(d, "notes.txt").also { it.writeText("hi") }
        s.purgePublished(); assertTrue(orphan.exists()); assertTrue(odd.exists())
    }
    @Test fun publishSuccessThenReceiptFailureIsDistinctAndKeepsDraft() {
        val s = DraftStore(dir()); val a = draft(s)
        File(a.path + ".saved").mkdir(); File(a.path + ".saved", "x").writeText("x")
        val output = File(dir(), "published.m4a")
        val destination = output.toURI().toString()
        try {
            publishAndTrack(s, a, { r: String -> r }) { a.copyTo(output); destination }; fail()
        } catch (e: PublishedButUntracked) { assertEquals(destination, e.result) }
        assertTrue(output.exists())
        assertTrue(a.exists()); assertEquals(listOf(a), s.list())
    }
    @Test fun publishFailureLeavesNoReceipt() {
        val s = DraftStore(dir()); val a = draft(s)
        try { publishAndTrack(s, a, { r: String -> r }) { throw IllegalStateException("insert failed") }; fail() }
        catch (_: IllegalStateException) { }
        assertFalse(s.isPublished(a)); assertEquals(listOf(a), s.list())
    }
}
