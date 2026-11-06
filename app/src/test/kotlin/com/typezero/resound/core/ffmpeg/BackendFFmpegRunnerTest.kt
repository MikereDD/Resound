package com.typezero.resound.core.ffmpeg

import com.typezero.resound.core.io.OperationFiles
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BackendFFmpegRunnerTest {
    private class Fake(val handle: Long = 7L) : NativeBackend {
        @Volatile var onComplete: ((FFmpegResult) -> Unit)? = null
        @Volatile var onStats: ((Long) -> Unit)? = null
        val cancelled = CopyOnWriteArrayList<Long>()
        val started = CountDownLatch(1)
        var blockStart: CountDownLatch? = null          // submission blocks until released
        val inStart = CountDownLatch(1)
        var failStart: Throwable? = null
        var completeDuringStart = false
        @Volatile var startCalls = 0
        override fun start(args: List<String>, onComplete: (FFmpegResult) -> Unit, onStats: (Long) -> Unit): Long {
            startCalls++
            inStart.countDown()
            blockStart?.let { assertTrue(it.await(10, TimeUnit.SECONDS)) }
            failStart?.let { throw it }
            this.onComplete = onComplete; this.onStats = onStats; started.countDown()
            if (completeDuringStart) onComplete(FFmpegResult(0, ""))
            return handle
        }
        override fun cancel(handle: Long) { cancelled += handle }
    }
    private fun tmp() = File.createTempFile("ff_", ".bin").also { it.deleteOnExit() }
    private val direct = java.util.concurrent.Executor { it.run() }
    private fun owner() = OperationFiles(direct)
    private fun await(l: CountDownLatch) = assertTrue(l.await(10, TimeUnit.SECONDS))

    // ---- ISSUE 1: cancel-before-handle (deterministic, latch-forced) ----
    @Test fun cancelObservedBeforeHandlePublishedTargetsExactlyThePublishedHandle() = runBlocking {
        val be = Fake(handle = 73019L).also { it.blockStart = CountDownLatch(1) }
        val observed = CountDownLatch(1)
        val o = owner(); val f = o.adopt(tmp())
        val r = BackendFFmpegRunner(be, graceMs = 10_000, onCancelObserved = { observed.countDown() })
        val job = launch(Dispatchers.Default) { try { r.run(listOf("x"), 0, o, null) } finally { o.deleteAll() } }
        await(be.inStart)                               // submission entered and is BLOCKED: no handle exists
        job.cancel()
        await(observed)                                 // cancellation handler ran while handle unpublished
        assertTrue("no cancel may be sent without a valid handle", be.cancelled.isEmpty())
        be.blockStart!!.countDown()                     // submission returns handle 73019
        await(be.started)
        withTimeout(10_000) { while (be.cancelled.isEmpty()) delay(5) }
        assertEquals(listOf(73019L), be.cancelled.toList())   // exact id; never 0 / placeholder
        assertTrue("files kept until terminal", f.exists())
        be.onComplete!!(FFmpegResult(-1, "")); job.join()
        assertFalse(f.exists()); assertEquals(listOf(73019L), be.cancelled.toList())  // exactly once
    }
    @Test fun publicationFirstThenCancelDispatchesOnce() = runBlocking {
        val be = Fake(handle = 55L); val o = owner()
        val job = launch(Dispatchers.Default) { BackendFFmpegRunner(be, 10_000).run(listOf("x"), 0, o, null) }
        await(be.started); job.cancel()
        withTimeout(10_000) { while (be.cancelled.isEmpty()) delay(5) }
        job.cancel(); delay(50)
        assertEquals(listOf(55L), be.cancelled.toList())
        be.onComplete!!(FFmpegResult(0, "")); job.join()
    }
    @Test fun terminalBeforePublicationNeverCancels() = runBlocking {
        val be = Fake().also { it.completeDuringStart = true }; val o = owner(); val f = o.adopt(tmp())
        val res = BackendFFmpegRunner(be).run(listOf("x"), 0, o, null)
        assertEquals(0, res.returnCode); assertTrue(be.cancelled.isEmpty())
        o.deleteAll(); assertFalse(f.exists())
    }
    @Test fun cancelledBeforeSubmissionStartsNothing() = runBlocking {
        val be = Fake(); val o = owner()
        val job = launch(start = CoroutineStart.LAZY) { BackendFFmpegRunner(be).run(listOf("x"), 0, o, null) }
        job.cancel(); job.join()
        assertEquals(0, be.startCalls); assertTrue(be.cancelled.isEmpty())
        o.deleteAll(); assertTrue(o.isCleaned)
    }

    // ---- ownership / grace behaviour (fake backend) ----
    @Test fun cancelThenTerminalBeforeGraceDeletesAfterTerminal() = runBlocking {
        val be = Fake(); val o = owner(); val inp = o.adopt(tmp()); val out = o.adopt(tmp())
        val job = launch(Dispatchers.Default) { try { BackendFFmpegRunner(be, 5_000).run(listOf("x"), 0, o, null) } finally { o.deleteAll() } }
        await(be.started); job.cancel()
        withTimeout(10_000) { while (be.cancelled.isEmpty()) delay(5) }; delay(100)
        assertTrue(inp.exists() && out.exists())
        be.onComplete!!(FFmpegResult(-1, "")); job.join()
        assertFalse(inp.exists() || out.exists())
    }
    @Test fun indefiniteNoncompletionRetainsFilesAfterCallerFinished() = runBlocking {
        val be = Fake(); val o = owner(); val inp = o.adopt(tmp()); val out = o.adopt(tmp())
        var expired = false
        val job = launch(Dispatchers.Default) {
            try { BackendFFmpegRunner(be, graceMs = 100, onGraceExpired = { expired = true }).run(listOf("x"), 0, o, null) } finally { o.deleteAll() }
        }
        await(be.started); job.cancelAndJoin()
        assertTrue(expired); assertTrue(inp.exists() && out.exists()); assertTrue(o.isQuarantined)
        be.onComplete!!(FFmpegResult(-1, ""))
        assertFalse(inp.exists() || out.exists()); assertTrue(o.isCleaned)
    }
    @Test fun synchronousSubmitFailureReleasesOwnership() = runBlocking {
        val be = Fake().also { it.failStart = IllegalStateException("boom") }; val o = owner(); val f = o.adopt(tmp())
        try { BackendFFmpegRunner(be).run(listOf("x"), 0, o, null); fail() } catch (_: IllegalStateException) { }
        o.deleteAll(); assertFalse(f.exists())
    }
    @Test fun runAfterOwnerClosedIsRejectedWithoutStartingNative() = runBlocking {
        val be = Fake(); val o = owner(); o.deleteAll()
        try { BackendFFmpegRunner(be).run(listOf("x"), 0, o, null); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, be.startCalls)
    }
    @Test fun lateProgressIgnoredAfterCancel() = runBlocking {
        val be = Fake(); val o = owner(); val seen = java.util.concurrent.atomic.AtomicInteger()
        val job = launch(Dispatchers.Default) { BackendFFmpegRunner(be, 50).run(listOf("x"), 1000, o, FFmpegProgress { _, _ -> seen.incrementAndGet() }) }
        await(be.started); be.onStats!!(100); assertEquals(1, seen.get())
        job.cancelAndJoin(); be.onStats!!(200); assertEquals(1, seen.get())
    }
    @Test fun independentSessionsHaveIndependentOwners() = runBlocking {
        val b1 = Fake(1); val b2 = Fake(2); val o1 = owner(); val o2 = owner()
        val f1 = o1.adopt(tmp()); val f2 = o2.adopt(tmp())
        val j1 = launch(Dispatchers.Default) { try { BackendFFmpegRunner(b1, 50).run(listOf("a"), 0, o1, null) } finally { o1.deleteAll() } }
        val j2 = launch(Dispatchers.Default) { try { BackendFFmpegRunner(b2, 5000).run(listOf("b"), 0, o2, null) } finally { o2.deleteAll() } }
        await(b1.started); await(b2.started)
        j1.cancelAndJoin()
        assertEquals(listOf(1L), b1.cancelled.toList()); assertTrue(b2.cancelled.isEmpty())
        assertTrue(f1.exists() && f2.exists())
        b1.onComplete!!(FFmpegResult(0, "")); assertFalse(f1.exists()); assertTrue(f2.exists())
        b2.onComplete!!(FFmpegResult(0, "")); j2.join(); assertFalse(f2.exists())
    }

    // ---- D2: submission off the caller thread ----
    @Test fun submissionRunsOnInjectedDispatcherNotCaller() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "ffsubmit-io") }
        val disp = pool.asCoroutineDispatcher()
        var startThread = ""
        val be = object : NativeBackend {
            override fun start(args: List<String>, onComplete: (FFmpegResult) -> Unit, onStats: (Long) -> Unit): Long {
                startThread = Thread.currentThread().name; onComplete(FFmpegResult(0, "")); return 5L
            }
            override fun cancel(handle: Long) {}
        }
        val o = owner()
        val res = runBlocking(CoroutineName("caller")) {
            val caller = Thread.currentThread().name
            val r = BackendFFmpegRunner(be, dispatcher = disp).run(listOf("x"), 0, o, null)
            assertNotEquals(caller, startThread); r
        }
        assertTrue(startThread, startThread.startsWith("ffsubmit-io")); assertEquals(0, res.returnCode)
        pool.shutdown()
    }
}
