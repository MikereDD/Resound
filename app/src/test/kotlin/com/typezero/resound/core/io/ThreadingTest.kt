package com.typezero.resound.core.io

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ThreadingTest {
    private val pool = Executors.newSingleThreadExecutor { Thread(it, "fake-io-thread") }
    private val io = pool.asCoroutineDispatcher()
    private val direct = java.util.concurrent.Executor { it.run() }

    @Test fun draftStoreConstructionDoesNoDiskWorkUntilUsed() {
        var resolved = 0
        val dir = java.nio.file.Files.createTempDirectory("lazy").toFile()
        val store = DraftStore.forContextForTest { resolved++; dir }
        assertEquals("construction must not resolve the directory", 0, resolved)
        store.list(); assertEquals(1, resolved); store.list(); assertEquals(1, resolved)
    }

    @Test fun allocationRunsOnIoDispatcherAndIsOwned() = runBlocking {
        val o = OperationFiles(direct); var thread = ""
        val f = allocateOwned(o, io) { thread = Thread.currentThread().name; File.createTempFile("al_", ".bin") }
        assertTrue(thread, thread.startsWith("fake-io-thread")); assertEquals(1, o.size)
        o.deleteAll(); assertFalse(f.exists())
    }

    @Test fun cancellationAfterIoAllocationBeforeDeliveryStillOwnsFile() = runBlocking {
        val o = OperationFiles(direct); val created = CompletableDeferred<File>(); val release = CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            allocateOwned(o, io) { File.createTempFile("al_", ".bin").also { created.complete(it); release.await(5, TimeUnit.SECONDS) } }
        }
        val f = created.await(); job.cancel(); release.countDown(); job.join()
        assertEquals(1, o.size); o.deleteAll(); assertFalse("no orphan", f.exists())
    }

    @Test fun allocationAfterOwnerClosedDeletesFreshFileAndThrows() = runBlocking {
        val o = OperationFiles(direct); o.deleteAll(); var made: File? = null
        try { allocateOwned(o, io) { File.createTempFile("al_", ".bin").also { made = it } }; fail() } catch (_: IllegalStateException) { }
        assertFalse(made!!.exists())
    }
}
