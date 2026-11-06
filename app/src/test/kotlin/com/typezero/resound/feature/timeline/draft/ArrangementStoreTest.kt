package com.typezero.resound.feature.timeline.draft

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ArrangementStoreTest {
    @Test fun oversizedSlotCannotBeDiscardedAndOverwrittenEvenBesideValidV1() {
        val d = Files.createTempDirectory("oversized-future").toFile()
        try {
            ArrangementStore(d).save(fixture(1))
            val b = java.io.File(d, "active-b.draft")
            java.io.RandomAccessFile(b, "rw").use { f ->
                f.writeInt(0x52534446); f.writeInt(2); f.setLength(ArrangementCodec.MAX_BYTES.toLong() + 1)
            }
            try { ArrangementStore(d).load(); fail("oversized slot must block") } catch (_: CorruptArrangement) { }
            try { ArrangementStore(d).save(fixture(3)); fail("must not replace unknown user data") } catch (_: CorruptArrangement) { }
            assertEquals(ArrangementCodec.MAX_BYTES.toLong() + 1, b.length())
            java.io.DataInputStream(b.inputStream()).use { it.readInt(); assertEquals(2, it.readInt()) }
        } finally { d.deleteRecursively() }
    }

    private fun dir() = Files.createTempDirectory("arrangement").toFile()
    @Test fun interruptedStageCannotReplaceLastValidSnapshot() {
        val d = dir()
        try {
            val store = ArrangementStore(d)
            store.save(fixture(1)); store.save(fixture(2))
            val interrupted = ArrangementStore(d) { throw java.io.IOException("interrupt before rename") }
            try { interrupted.save(fixture(3)); fail() } catch (_: java.io.IOException) { }
            assertEquals(fixture(2), ArrangementStore(d).load())
            assertFalse(d.listFiles()!!.any { it.extension == "pending" })
        } finally { d.deleteRecursively() }
    }
    @Test fun orphanStageFromProcessDeathIsNotACommit() {
        val d = dir()
        try {
            ArrangementStore(d).save(fixture(1))
            java.io.File(d, "active-orphan.pending").writeBytes(ArrangementCodec.encode(fixture(99)))
            assertEquals(fixture(1), ArrangementStore(d).load())
        } finally { d.deleteRecursively() }
    }
    @Test fun corruptedNewestSlotFallsBackWithoutTouchingAudio() {
        val d = dir()
        val audio = java.io.File(d, "precious.wav").also { it.writeText("unchanged") }
        try {
            ArrangementStore(d).save(fixture(1)); ArrangementStore(d).save(fixture(2))
            java.io.File(d, "active-b.draft").writeBytes(byteArrayOf(1,2,3))
            assertEquals(fixture(1), ArrangementStore(d).load())
            ArrangementStore(d).save(fixture(3))
            assertEquals(fixture(3), ArrangementStore(d).load())
            assertEquals("unchanged", audio.readText())
        } finally { d.deleteRecursively() }
    }
    @Test fun entirelyCorruptDraftBlocksOverwriteRatherThanStartingBlank() {
        val d = dir()
        try {
            val a = java.io.File(d, "active-a.draft").also { it.writeText("bad-original") }
            try { ArrangementStore(d).load(); fail() } catch (_: CorruptArrangement) { }
            try { ArrangementStore(d).save(fixture()); fail() } catch (_: CorruptArrangement) { }
            assertEquals("bad-original", a.readText())
        } finally { d.deleteRecursively() }
    }
    @Test fun futureSchemaInEitherSlotBlocksLoadAndAutosaveWithoutReplacingIt() {
        val d = dir()
        try {
            ArrangementStore(d).save(fixture(1))
            val bytes = ArrangementCodec.encode(fixture(2))
            java.nio.ByteBuffer.wrap(bytes).putInt(4, 55)
            val b = java.io.File(d, "active-b.draft").also { it.writeBytes(bytes) }
            try { ArrangementStore(d).load(); fail() } catch (_: UnsupportedDraftSchema) { }
            try { ArrangementStore(d).save(fixture(3)); fail() } catch (_: UnsupportedDraftSchema) { }
            assertArrayEquals(bytes, b.readBytes())
        } finally { d.deleteRecursively() }
    }
    @Test fun staleWriterCannotReplaceANewerCommittedRevision() {
        val d = dir()
        try {
            assertTrue(ArrangementStore(d).save(fixture(8)))
            assertFalse(ArrangementStore(d).save(fixture(7)))
            assertFalse(ArrangementStore(d).save(fixture(8)))
            assertEquals(fixture(8), ArrangementStore(d).load())
        } finally { d.deleteRecursively() }
    }
    @Test fun concurrentStoreInstancesKeepNewestValidRevisionAndNoPartialSnapshots() {
        val d = dir()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val go = java.util.concurrent.CountDownLatch(1)
        try {
            val futures = (1L..40L).map { rev -> pool.submit {
                check(go.await(5, java.util.concurrent.TimeUnit.SECONDS))
                ArrangementStore(d).save(fixture(rev))
                val loaded = ArrangementStore(d).load()!!
                assertEquals(fixture(loaded.revision), loaded)
            } }
            go.countDown(); futures.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(fixture(40), ArrangementStore(d).load())
            assertTrue(d.listFiles()!!.count { it.extension == "draft" } <= 2)
        } finally { pool.shutdownNow(); d.deleteRecursively() }
    }

    @Test fun newOwnerRecoversCommittedArrangementWithoutAudioCopies() {
        val dir = Files.createTempDirectory("arrangement").toFile()
        try {
            assertTrue(ArrangementStore(dir).save(fixture()))
            assertEquals(fixture(), ArrangementStore(dir).load())
            assertEquals(1, dir.listFiles()!!.count { it.extension == "draft" })
        } finally { dir.deleteRecursively() }
    }
}
