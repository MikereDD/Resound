package com.typezero.resound.core.io

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OwnershipTest {
    private fun tmp() = File.createTempFile("own_", ".bin").also { it.deleteOnExit() }

    /** Cancellation racing the withContext return: file must already be owned. */
    @Test fun registeredBeforeSuspensionSurvivesReturnCancellation() = runBlocking {
        val owned = OperationFiles(Runnable::run)
        val created = CompletableDeferred<File>()
        val job = launch(Dispatchers.Default) {
            withContext(Dispatchers.IO) {
                val f = tmp(); owned.add(f); created.complete(f)
                Thread.sleep(50)
                f
            }
        }
        val f = created.await()
        job.cancel()   // cancel while/after IO block finished, before caller receives value
        job.join()
        assertEquals(1, owned.size)
        owned.deleteAll()
        assertFalse(f.exists())
    }

    @Test fun committedBlockRunsToCompletionDespiteCancellation() = runBlocking {
        var finished = false
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            runCommitted { started.complete(Unit); Thread.sleep(150); finished = true }
        }
        started.await(); job.cancelAndJoin()
        assertTrue(finished)
    }
}
