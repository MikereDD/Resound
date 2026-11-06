package com.typezero.resound.feature.edit

import com.typezero.resound.core.io.DraftStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecoveryMediaReadTest {
    private fun fixture(): File = Files.createTempDirectory(
        File(checkNotNull(System.getProperty("java.io.tmpdir"))).toPath(), "health-read-"
    ).toFile().canonicalFile

    @Test fun cancellationFromCleanupPropagatesAndProtectsLease() = runBlocking {
        val dir = fixture()
        try {
            val store = DraftStore(dir)
            val file = store.newFile("recording", "m4a").apply { writeText("original") }
            val read = RecoveryMediaRead {
                object : RecoveryMediaRead.Reader {
                    override fun inspect(file: File) = RecoveryReadOutcome.UNVERIFIED
                    override fun close() { throw CancellationException("cleanup interrupted") }
                }
            }
            var cancelled = false
            try {
                store.withRecovery(store.candidate(file)!!) { admitted, lease -> read.check(admitted, lease) }
            } catch (c: CancellationException) { cancelled = true }
            assertTrue("cleanup cancellation must not be swallowed", cancelled)
            assertNull(store.candidate(file))
            assertEquals("original", file.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun accessUnsupportedAndPlatformErrorsAreInconclusiveButCancellationEscapes() = runBlocking {
        val dir = fixture()
        try {
            val store = DraftStore(dir)
            val file = store.newFile("recording", "m4a").apply { writeText("original") }
            for (failure in listOf(SecurityException("denied"), java.io.IOException("unsupported"),
                IllegalStateException("platform"), CancellationException("cancelled"))) {
                var closed = false
                val read = RecoveryMediaRead {
                    object : RecoveryMediaRead.Reader {
                        override fun inspect(file: File): RecoveryReadOutcome = throw failure
                        override fun close() { closed = true }
                    }
                }
                var cancelled = false
                try {
                    store.withRecovery(store.candidate(file)!!) { admitted, lease ->
                        assertEquals(RecoveryReadOutcome.UNVERIFIED, read.check(admitted, lease))
                    }
                } catch (c: CancellationException) { cancelled = true }
                assertEquals(failure is CancellationException, cancelled)
                assertTrue(closed)
                assertNotNull(store.candidate(file))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun cleanupFailureRetainsRealLeaseBeforeCancelledIoReturn() = runBlocking {
        val dir = fixture()
        try {
            val store = DraftStore(dir)
            val file = store.newFile("recording", "m4a").apply { writeText("original") }
            lateinit var operation: Deferred<Unit>
            var continuation = false
            val read = RecoveryMediaRead {
                object : RecoveryMediaRead.Reader {
                    override fun inspect(file: File) = RecoveryReadOutcome.UNVERIFIED
                    override fun close() {
                        operation.cancel() // Cancel at the dispatcher-return boundary, not after delivery.
                        throw java.io.IOException("release not acknowledged")
                    }
                }
            }
            supervisorScope {
                operation = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    store.withRecovery(store.candidate(file)!!) { admitted, lease ->
                        read.check(admitted, lease)
                        continuation = true
                    }
                }
                operation.start()
                runCatching { operation.await() }
            }
            assertFalse(continuation)
            assertNull("uncertain read must protect the actual admission lease even without result delivery", store.candidate(file))
            assertEquals("original", file.readText())
            assertTrue(store.listCandidates().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun successfulMetadataAndEncodedSampleAreNotPlaybackProof() = runBlocking {
        val dir = fixture()
        try {
            val store = DraftStore(dir)
            val file = store.newFile("recording", "m4a").apply { writeText("original") }
            var closed = false
            var onIo = false
            val caller = Thread.currentThread()
            val read = RecoveryMediaRead {
                object : RecoveryMediaRead.Reader {
                    override fun inspect(file: File): RecoveryReadOutcome {
                        onIo = Thread.currentThread() !== caller
                        assertEquals("original", file.readText())
                        return RecoveryMediaRead.classify(true, 1000L, 4)
                    }
                    override fun close() { closed = true }
                }
            }
            store.withRecovery(store.candidate(file)!!) { admitted, lease ->
                assertEquals(RecoveryReadOutcome.UNVERIFIED, read.check(admitted, lease))
                assertTrue(closed)
            }
            assertTrue(onIo)
            assertNotNull(store.candidate(file))
            assertEquals(RecoveryReadOutcome.NO_USABLE_MEDIA, RecoveryMediaRead.classify(false, null, null))
            assertEquals(RecoveryReadOutcome.NO_USABLE_MEDIA, RecoveryMediaRead.classify(true, 0L, null))
            assertEquals(RecoveryReadOutcome.NO_USABLE_MEDIA, RecoveryMediaRead.classify(true, 1000L, -1))
            assertEquals(RecoveryReadOutcome.UNVERIFIED, RecoveryMediaRead.classify(true, null, null))
        } finally { dir.deleteRecursively() }
    }
}
