package com.typezero.resound.core.io

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PendingPublicationTest {
    @Test fun productionGateIncludesInsertWriteFinalizeReadbackAndFailureCleanup() {
        listOf("insert", "write", "finalize", "readback", "cleanup").forEach { phase ->
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val secondThread = AtomicReference<Thread>()
            val inserts = AtomicInteger()
            val pool = Executors.newFixedThreadPool(2)
            fun barrier(at: String) {
                if (at == phase) { held.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            }
            try {
                val first = pool.submit {
                    try {
                        publishPendingItem(
                            insert = { barrier("insert"); "first" },
                            write = { if (phase == "cleanup") error("write failed") else barrier("write") },
                            finalize = { barrier("finalize") },
                            readback = { barrier("readback"); "provider-name" },
                            cleanup = { barrier("cleanup") },
                        )
                    } catch (e: IllegalStateException) { if (phase != "cleanup") throw e }
                }
                assertTrue(held.await(5, TimeUnit.SECONDS))
                val second = pool.submit {
                    secondThread.set(Thread.currentThread())
                    publishPendingItem(insert = { inserts.incrementAndGet(); "second" },
                        write = {}, finalize = {}, readback = { "second-name" }, cleanup = {})
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (secondThread.get()?.let(PublicationGate::isQueued) != true && System.nanoTime() < deadline) Thread.yield()
                assertTrue("second publisher queued during $phase", secondThread.get()?.let(PublicationGate::isQueued) == true)
                assertEquals("no overlapping insert during $phase", 0, inserts.get())
                release.countDown()
                first.get(5, TimeUnit.SECONDS)
                second.get(5, TimeUnit.SECONDS)
                assertEquals(1, inserts.get())
            } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }

    @Test fun metadataFailureAfterFinalizationDoesNotDeletePublishedAudio() {
        var finalized = false
        var deleted = false
        val result = publishPendingItem(insert = { "new-uri" }, write = {}, finalize = { finalized = true },
            readback = { throw IllegalStateException("query denied") }, cleanup = { deleted = true })
        assertTrue(finalized)
        assertFalse(deleted)
        assertEquals("new-uri", result.first)
        assertNull(result.second)
    }

    @Test fun preFinalizationFailureDeletesOnlyInsertedItemAndPropagatesCleanupFailure() {
        val deleted = mutableListOf<String>()
        try {
            publishPendingItem(insert = { "owned-new-uri" }, write = { error("copy") }, finalize = {},
                readback = { "unused" }, cleanup = { deleted += it; error("cleanup") })
            fail("failure expected")
        } catch (e: IllegalStateException) {
            assertEquals("copy", e.message)
            assertEquals("cleanup", e.suppressed.single().message)
        }
        assertEquals(listOf("owned-new-uri"), deleted)
    }
}
