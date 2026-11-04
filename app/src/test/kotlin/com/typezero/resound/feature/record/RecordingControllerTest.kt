package com.typezero.resound.feature.record

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class RecordingControllerTest {
    private class FakeRecorder(val stopDelayMs: Long = 0, val startFails: Boolean = false) : Recorder {
        override var isRecording = false
        override var terminationAcknowledged = true // explicit fake-backend acknowledgement
        val threads = java.util.concurrent.CopyOnWriteArrayList<String>()
        val stops = java.util.concurrent.atomic.AtomicInteger()
        override fun start(outputPath: String) {
            threads += Thread.currentThread().name
            if (startFails) throw IllegalStateException("start")
            terminationAcknowledged = false
            isRecording = true
        }
        override fun stop(): String? { threads += Thread.currentThread().name; Thread.sleep(stopDelayMs); stops.incrementAndGet(); isRecording = false; terminationAcknowledged = true; return "/p" }
    }
    private val ioPool = Executors.newFixedThreadPool(2) { Thread(it, "fake-io") }
    private val io = ioPool.asCoroutineDispatcher()
    private fun ctl(r: Recorder, scope: CoroutineScope) = RecordingController(r, scope, io)

    @Test fun startAndSlowStopRunOffCallerThread() = runBlocking(CoroutineName("main-sim")) {
        val r = FakeRecorder(stopDelayMs = 300); val c = ctl(r, CoroutineScope(SupervisorJob()))
        val caller = Thread.currentThread().name
        val t = c.start("/p")
        val t0 = System.nanoTime()
        val path = c.stop(t)
        assertEquals("/p", path)
        assertTrue(r.threads.toString(), r.threads.all { it.startsWith("fake-io") && !it.startsWith(caller.substringBefore(" @")) })
        assertTrue(c.timings.first { it.op == "stop" }.millis >= 250)   // fake latency only; NOT real MediaRecorder
        assertEquals(0L, c.active)
    }
    @Test fun lateDisposalStopCannotEndNewerRecording() = runBlocking {
        val r = FakeRecorder(); val c = ctl(r, CoroutineScope(SupervisorJob()))
        val old = c.start("/a"); c.stop(old)
        val fresh = c.start("/b")
        assertNull(c.stop(old))                 // stale token
        assertTrue(r.isRecording); assertEquals(fresh, c.active)
    }
    @Test fun stopSurvivesCallerCancellation() = runBlocking {
        val r = FakeRecorder(stopDelayMs = 150); val c = ctl(r, CoroutineScope(SupervisorJob()))
        val t = c.start("/a")
        val job = launch(Dispatchers.Default) { c.stop(t) }
        delay(30); job.cancelAndJoin()
        assertEquals(1, r.stops.get()); assertFalse(r.isRecording); assertEquals(0L, c.active)
    }
    @Test fun detachedStopRunsInSurvivingScope() = runBlocking {
        val r = FakeRecorder(); val appScope = CoroutineScope(SupervisorJob())
        val c = ctl(r, appScope); val t = c.start("/a")
        val screen = CoroutineScope(Job()); screen.cancel()
        c.stopDetached(t).join()
        assertFalse(r.isRecording); assertEquals(1, r.stops.get())
    }
    @Test fun startFailureLeavesNothingActive() = runBlocking {
        val c = ctl(FakeRecorder(startFails = true), CoroutineScope(SupervisorJob()))
        try { c.start("/a"); fail() } catch (_: IllegalStateException) { }
        assertEquals(0L, c.active)
    }
    @Test fun startAndStopAreSerialized() = runBlocking {
        val r = FakeRecorder(stopDelayMs = 100); val c = ctl(r, CoroutineScope(SupervisorJob()))
        val t = c.start("/a")
        val s = async(Dispatchers.Default) { c.stop(t) }
        delay(20)
        val n = async(Dispatchers.Default) { c.start("/b") }   // must wait for stop to finish
        s.await(); n.await()
        assertTrue(r.isRecording)
    }
}
