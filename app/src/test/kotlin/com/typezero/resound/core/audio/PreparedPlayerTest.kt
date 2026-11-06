package com.typezero.resound.core.audio

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreparedPlayerTest {
    private val owners = mutableListOf<PreparedPlayer>()
    private fun player(dispatcher: CoroutineDispatcher = Dispatchers.IO) =
        PreparedPlayer(dispatcher).also { owners += it }
    @org.junit.After fun drainOwners() = runBlocking {
        owners.forEach { it.release() }
        withTimeout(10_000) { owners.forEach { it.awaitClosed() } }
    }
    private open class Backend(var fail: Boolean = false) : PlayerBackend {
        var released = 0; var paused = 0; var starts = 0
        override fun prepare() { if (fail) error("prepare") }
        override fun start() { starts++ }
        override fun pause() { paused++ }
        override fun seekTo(ms: Long) {}
        override fun release() { released++ }
        override val isPlaying get() = starts > 0
        override val currentMs = 0L
    }
    /** Models the documented native states; pause is illegal before start or after error. */
    private class NativeStateBackend : PlayerBackend {
        var state = "Idle"
        var pauses = 0
        var releases = 0
        private var notifyFailure: () -> Unit = {}
        override fun onFailure(handler: () -> Unit) { notifyFailure = handler }
        override fun prepare() { state = "Prepared" }
        override fun start() { check(state == "Prepared" || state == "Paused"); state = "Started" }
        override fun pause() {
            pauses++
            check(state in setOf("Started", "Paused", "PlaybackCompleted")) { "pause in $state" }
            state = "Paused"
        }
        override fun seekTo(ms: Long) {}
        override fun release() { releases++; state = "Released" }
        override val isPlaying get() = state == "Started"
        override val healthy get() = state != "Error"
        override val currentMs = 0L
        fun nativeFailure() { state = "Error"; notifyFailure() }
    }
    @Test fun retirementOfPreparedUnstartedSourceNeverPauses() = runBlocking {
        val owner = player()
        val a = NativeStateBackend()
        owner.prepare("A") { a }
        owner.invalidate(); owner.awaitRetirement()
        val b = NativeStateBackend()
        owner.prepare("B") { b }
        assertEquals(0, a.pauses); assertEquals(1, a.releases)
        assertTrue("unstarted A must not poison replacement B", owner.readyFor("B"))
    }
    @Test fun retirementAfterNativeErrorNeverPausesOrPoisonsReplacement() = runBlocking {
        val owner = player()
        val a = NativeStateBackend()
        owner.prepare("A") { a }; a.nativeFailure()
        owner.awaitRetirement()
        val b = NativeStateBackend()
        owner.prepare("B") { b }
        assertEquals(0, a.pauses); assertEquals(1, a.releases)
        assertTrue("native error retirement must permit a coherent new source", owner.readyFor("B"))
    }
    @Test fun cancelledClosedOwnerStillRetiresLateCandidateExactlyOnce() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val b = object : Backend() {
            override fun prepare() { entered.countDown(); check(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        }
        val owner = player()
        val prepare = launch(Dispatchers.Default) { owner.prepare("A") { b } }
        check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        owner.release(); prepare.cancel()
        val closed = launch(start = CoroutineStart.UNDISPATCHED) { owner.awaitClosed() }
        try { assertFalse("close must retain late producer ownership", closed.isCompleted) }
        finally { resume.countDown(); prepare.join(); withTimeout(10_000) { closed.join() } }
        assertEquals(0, b.paused); assertEquals(1, b.released)
        assertFalse(owner.readyFor("A"))
    }
    @Test fun revocationAndDisposalStayPromptWhileNativeReleaseIsBlocked() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val owner = player()
        val b = object : Backend() {
            override fun release() { entered.countDown(); check(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)); super.release() }
        }
        owner.prepare("A") { b }
        try {
            val revoked = pool.submit { owner.invalidate() }
            check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            revoked.get(2, java.util.concurrent.TimeUnit.SECONDS)
            pool.submit { assertFalse(owner.readyFor("A")); owner.release() }.get(2, java.util.concurrent.TimeUnit.SECONDS)
        } finally { resume.countDown(); pool.shutdown(); pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); owner.release() }
        Unit
    }
    @Test fun readinessIsObservationalEvenWhenBackendBecomesUnhealthy() = runBlocking {
        var healthy = true
        val b = object : Backend() { override val healthy get() = healthy }
        val owner = player()
        owner.prepare("A") { b }
        healthy = false
        assertFalse(owner.readyFor("A"))
        assertEquals("readiness must not retire native resources", 0, b.paused)
        assertEquals(0, b.released)
        owner.release()
    }
    @Test fun closingDuringPreparationRejectsAndReleasesCandidate() = runBlocking {
        val owner = player(Dispatchers.Unconfined)
        val b = object : Backend() { override fun prepare() { owner.release() } }
        try { owner.prepare("B") { b }; fail("closed preparation installed") } catch (_: CancellationException) {}
        owner.awaitClosed()
        assertFalse(owner.readyFor("B")); assertEquals(1, b.released)
    }
    @Test fun seekPauseAndPollingErrorsRevokeReadiness() = runBlocking {
        for (stage in listOf("seek", "pause", "position", "playing")) {
            val b = object : Backend() {
                override fun seekTo(ms: Long) { if (stage == "seek") error(stage) }
                override fun pause() { if (stage == "pause") error(stage) }
                override val currentMs get(): Long { if (stage == "position") error(stage); return 0 }
                override val isPlaying get(): Boolean { if (stage == "playing") error(stage); return false }
            }
            val owner = player(Dispatchers.Unconfined)
            owner.prepare("B") { b }
            try {
                when (stage) {
                    "seek" -> owner.seekTo("B", 20)
                    "pause" -> owner.pause()
                    "position" -> owner.currentMs
                    else -> owner.isPlaying
                }
                fail("$stage did not expose error")
            } catch (_: IllegalStateException) {}
            assertFalse(stage, owner.readyFor("B"))
            owner.release(); owner.awaitClosed()
            assertEquals(stage, 1, b.released)
            assertNotNull(owner.failure)
        }
    }
    @Test fun stalePreparationCannotChangeNewerReadySession() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val a = object : Backend() {
            override fun prepare() { entered.countDown(); check(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            override fun release() { super.release(); error("stale cleanup") }
        }
        val b = Backend()
        val owner = player()
        val old = launch(Dispatchers.Default) { owner.prepare("A") { a } }
        check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        owner.prepare("B") { b }
        resume.countDown(); old.join()
        owner.awaitRetirement()
        assertTrue(owner.readyFor("B")); assertFalse(owner.readyFor("A"))
        assertNull("stale cleanup republished failure", owner.failure)
        assertEquals(1, a.released); assertEquals(0, b.released)
        owner.release()
    }
    @Test fun cleanupErrorsCannotMaskPrepareFailureOrSkipRelease() = runBlocking {
        val primary = IllegalArgumentException("prepare")
        val b = object : Backend() {
            override fun prepare() { throw primary }
            override fun release() { super.release(); throw AssertionError("release cleanup") }
        }
        val owner = player(Dispatchers.Unconfined)
        try { owner.prepare("B") { b }; fail("expected") } catch (t: Throwable) { assertSame(primary, t) }
        owner.awaitRetirement()
        assertEquals(1, b.released)
        assertFalse(owner.readyFor("B"))
    }
    @Test fun asynchronousBackendErrorRevokesReadiness() = runBlocking {
        var healthy = true
        var notify: () -> Unit = {}
        val b = object : Backend() {
            override val healthy get() = healthy
            override fun onFailure(handler: () -> Unit) { notify = handler }
        }
        val owner = player(Dispatchers.Unconfined)
        owner.prepare("B") { b }
        healthy = false
        notify()
        owner.awaitRetirement()
        assertFalse("native error must revoke readiness", owner.readyFor("B"))
        assertEquals(1, b.released)
    }
    @Test fun promptCancellationReleasesCandidate() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val b = object : Backend() { override fun prepare() { entered.countDown(); check(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)) } }
        val owner = player()
        val job = launch(Dispatchers.Default) { owner.prepare("B") { b } }
        check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel(); resume.countDown(); job.join()
        owner.awaitRetirement()
        assertEquals("candidate returned across cancelled dispatcher boundary", 1, b.released)
        assertFalse(owner.readyFor("B"))
    }
    @Test fun transportFailureInvalidatesReadiness() = runBlocking {
        val b = object : Backend() { override fun start() { error("start") } }
        val owner = player(Dispatchers.Unconfined)
        owner.prepare("B") { b }
        try { owner.play("B", 0); fail("expected") } catch (_: IllegalStateException) {}
        owner.awaitRetirement()
        assertFalse(owner.readyFor("B")); assertEquals(1, b.released)
    }
    @Test fun failedAcceptedSwitchCannotPlayOldSource() = runBlocking {
        val owner = player(Dispatchers.Unconfined)
        val a = Backend(); val b = Backend(true)
        owner.prepare("A") { a }; owner.play("A", 0)
        try { owner.prepare("B") { b }; fail("expected") } catch (_: IllegalStateException) {}
        owner.awaitRetirement()
        assertFalse("A must not remain ready under accepted B", owner.readyFor("A"))
        assertFalse(owner.readyFor("B"))
        assertEquals(0, a.paused); assertEquals(1, a.released); assertEquals(1, b.released)
        try { owner.play("B", 0); fail("guard") } catch (_: IllegalStateException) {}
        assertEquals(1, a.starts)
    }
}
