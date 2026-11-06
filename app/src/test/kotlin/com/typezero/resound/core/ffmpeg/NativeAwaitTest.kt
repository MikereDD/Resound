package com.typezero.resound.core.ffmpeg

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeAwaitTest {
    @Test fun cleanupRunsOnlyAfterNativeTerminalCallback() = runBlocking {
        val done = CompletableDeferred<Int>()
        val events = CopyOnWriteArrayList<String>()
        val job = launch(Dispatchers.Default) {
            try {
                awaitNativeCompletion(done, requestCancel = { events += "cancel-requested" })
            } finally {
                events += "cleanup"
            }
        }
        delay(50)
        job.cancel()
        delay(200)
        assertEquals("cleanup must wait for native", listOf("cancel-requested"), events.toList())
        events += "native-terminal"; done.complete(-1)
        job.join()
        assertEquals(listOf("cancel-requested", "native-terminal", "cleanup"), events.toList())
    }

    @Test fun graceTimeoutReportsAndStillPropagatesCancellation() = runBlocking {
        val done = CompletableDeferred<Int>()
        var expired = false
        val job = launch(Dispatchers.Default) {
            awaitNativeCompletion(done, requestCancel = {}, graceMs = 100, onGraceExpired = { expired = true })
        }
        delay(30); job.cancelAndJoin()
        assertTrue(expired); assertTrue(job.isCancelled)
    }

    @Test fun normalCompletionReturnsResult() = runBlocking {
        val done = CompletableDeferred<Int>().also { it.complete(7) }
        assertEquals(7, awaitNativeCompletion(done, requestCancel = { fail() }))
    }

    @Test fun independentSessionsCancelIndependently() = runBlocking {
        val a = CompletableDeferred<Int>(); val b = CompletableDeferred<Int>()
        var cancelledA = false; var cancelledB = false
        val ja = launch(Dispatchers.Default) { awaitNativeCompletion(a, { cancelledA = true }) }
        val jb = launch(Dispatchers.Default) { awaitNativeCompletion(b, { cancelledB = true }) }
        delay(30); ja.cancel(); delay(50); a.complete(0); ja.join()
        assertTrue(cancelledA); assertFalse(cancelledB); assertTrue(jb.isActive)
        b.complete(1); jb.join()
    }
}
