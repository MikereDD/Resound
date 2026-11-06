package com.typezero.resound.core.io

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

class OperationFilesTest {
    private fun tmp() = File.createTempFile("op_", ".bin").also { it.deleteOnExit() }
    private val direct = Executor { it.run() }

    @Test fun deletesImmediatelyWhenNoNative() {
        val o = OperationFiles(direct); val f = o.adopt(tmp()); o.deleteAll()
        assertFalse(f.exists()); assertTrue(o.isCleaned)
    }
    @Test fun deferredUntilNativeTerminal() {
        val o = OperationFiles(direct); val a = o.adopt(tmp()); val b = o.adopt(tmp())
        assertTrue(o.nativeStarted()); o.deleteAll()
        assertTrue(a.exists() && b.exists()); assertTrue(o.isQuarantined); assertFalse(o.isClosed)
        o.nativeTerminal()
        assertFalse(a.exists() || b.exists()); assertTrue(o.isCleaned)
    }
    @Test fun neverTerminalKeepsFilesIndefinitely() {
        val o = OperationFiles(direct); val f = o.adopt(tmp())
        o.nativeStarted(); o.deleteAll(); o.deleteAll()
        assertTrue(f.exists()); assertTrue(o.isQuarantined)
    }
    @Test fun registrationWhileDrainingIsRetainedAndDeletedAtTerminal() {
        val o = OperationFiles(direct); o.nativeStarted(); o.deleteAll()
        val late = tmp(); assertTrue(o.add(late))
        assertTrue(late.exists()); o.nativeTerminal(); assertFalse(late.exists())
    }
    @Test fun registrationAfterCleanupIsRejectedAndAdoptDeletesIt() {
        val o = OperationFiles(direct); o.deleteAll()
        val f = tmp(); assertFalse(o.add(f)); assertTrue("add() must not delete caller file", f.exists())
        val g = tmp()
        try { o.adopt(g); fail() } catch (_: IllegalStateException) { }
        assertFalse("adopt() deletes the rejected fresh file", g.exists())
    }
    @Test fun nativeStartRejectedAfterClosure() {
        val o = OperationFiles(direct); o.deleteAll()
        assertFalse(o.nativeStarted())
        o.nativeTerminal()   // no underflow, no state change
        assertTrue(o.isCleaned)
    }
    @Test fun terminalWithoutStartDoesNotUnderflowOrCleanEarly() {
        val o = OperationFiles(direct); val f = o.adopt(tmp())
        o.nativeTerminal(); o.nativeTerminal()
        assertTrue(f.exists()); assertTrue(o.nativeStarted()); o.deleteAll(); assertTrue(f.exists())
        o.nativeTerminal(); assertFalse(f.exists())
    }
    @Test fun repeatedCleanupAndTerminalDeleteExactlyOnce() {
        val runs = AtomicInteger()
        val o = OperationFiles { runs.incrementAndGet(); it.run() }
        val f = o.adopt(tmp()); o.nativeStarted(); o.deleteAll(); o.deleteAll()
        o.nativeTerminal(); o.nativeTerminal(); o.deleteAll()
        assertFalse(f.exists()); assertEquals(1, runs.get())
    }
    @Test fun twoSessionsNeedBothTerminals() {
        val o = OperationFiles(direct); val f = o.adopt(tmp())
        o.nativeStarted(); o.nativeStarted(); o.deleteAll()
        o.nativeTerminal(); assertTrue(f.exists())
        o.nativeTerminal(); assertFalse(f.exists())
    }
    @Test fun deleteFailureIsNotReportedAsCleaned() {
        val d = java.nio.file.Files.createTempDirectory("opd").toFile()
        val sub = File(d, "x").also { it.mkdir() }; File(sub, "child").writeText("c")   // non-empty dir: delete fails
        val o = OperationFiles(direct); o.adopt(sub); o.deleteAll()
        assertFalse(o.isCleaned); assertTrue(o.isClosed); assertEquals(listOf(sub), o.failedToDelete)
        d.deleteRecursively()
    }
    /** Stress: every concurrently registered file is either deleted, or add() said false and we still own it. */
    @Test fun concurrentRegistrationVsCleanupNeverOrphans() {
        repeat(300) {
            val o = OperationFiles(direct)
            val files = List(8) { tmp() }
            val accepted = java.util.concurrent.ConcurrentHashMap<File, Boolean>()
            val go = CountDownLatch(1)
            val ts = files.map { f -> Thread { go.await(); accepted[f] = o.add(f) } }
            val cleaner = Thread { go.await(); o.deleteAll() }
            (ts + cleaner).forEach { it.start() }; go.countDown(); (ts + cleaner).forEach { it.join() }
            files.forEach { f ->
                if (accepted[f] == true) assertFalse("accepted file orphaned", f.exists())
                else { assertTrue(f.exists()); f.delete() }
            }
        }
    }
    @Test fun concurrentTerminalAndCleanupRaceDeletesOnce() {
        repeat(300) {
            val runs = AtomicInteger(); val o = OperationFiles { runs.incrementAndGet(); it.run() }
            val f = o.adopt(tmp()); o.nativeStarted()
            val go = CountDownLatch(1)
            val a = Thread { go.await(); o.nativeTerminal() }; val b = Thread { go.await(); o.deleteAll() }
            a.start(); b.start(); go.countDown(); a.join(); b.join()
            assertFalse(f.exists()); assertEquals(1, runs.get())
        }
    }
}
