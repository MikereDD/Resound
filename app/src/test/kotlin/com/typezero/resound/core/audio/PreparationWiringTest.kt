package com.typezero.resound.core.audio

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreparationWiringTest {
    private open class Backend : PlayerBackend {
        var releases = 0
        var prepares = 0
        var callback: () -> Unit = {}
        override fun onFailure(handler: () -> Unit) { callback = handler }
        override fun prepare() { prepares++ }
        override fun start() {}
        override fun pause() {}
        override fun seekTo(ms: Long) {}
        override fun release() { releases++ }
        override val isPlaying = false
        override val currentMs = 0L
    }
    private class ConstructionFailure(val tag: String) : IllegalArgumentException(tag)
    @Test fun decoratorFailureReleasesCandidateAndSuppressesCleanup() = runBlocking {
        val owner = PreparedPlayer()
        val primary = ConstructionFailure("decorate")
        val cleanup = IllegalStateException("cleanup")
        var io = false
        val backend = object : Backend() {
            override fun release() { io = Thread.currentThread().name.contains("DefaultDispatcher"); super.release(); throw cleanup }
        }
        try {
            try {
                owner.prepareBound("A") { ticket ->
                    allocatePlayerBackend(ticket, PlayerBackendFactory { { throw primary } }) { backend }
                }
                fail("construction succeeded")
            } catch (t: IllegalArgumentException) {
                assertSame(primary, t); assertArrayEquals(arrayOf(cleanup), t.suppressed)
            }
            owner.awaitRetirement()
            assertEquals(1, backend.releases); assertTrue(io); assertFalse(owner.readyFor("A"))
        } finally { owner.release(); withContext(NonCancellable) { withTimeout(5000) { owner.awaitClosed() } } }
    }
    @Test fun errorBeforeInstallationCannotBecomeReady() = runBlocking {
        val owner = PreparedPlayer()
        val backend = object : Backend() { override fun onFailure(handler: () -> Unit) { handler() } }
        try {
            try { owner.prepare("A") { backend } } catch (_: IllegalStateException) {}
            assertFalse("early callback must revoke candidate even without backend health support", owner.readyFor("A"))
        } finally { owner.release(); withContext(NonCancellable) { withTimeout(5000) { owner.awaitClosed() } } }
        assertEquals(1, backend.releases)
    }
    @Test fun defaultRetirementTimeoutIsBoundedAndWorkerStillDrains() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val owner = PreparedPlayer(retirementBudgetMs = 20) // default awaitPolicy, not a replacement
        val a = object : Backend() { override fun release() {
            entered.countDown(); check(gate.await(5, java.util.concurrent.TimeUnit.SECONDS)); super.release()
        } }
        try {
            owner.prepare("A") { a }; owner.invalidate()
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            var allocated = false
            try { withTimeout(2000) { owner.prepare("B") { allocated = true; Backend() } }; fail("timeout missing") }
            catch (t: IllegalStateException) { assertTrue(t.message!!.contains("retirement timed out")) }
            assertFalse(allocated); assertFalse(owner.readyFor("A")); assertFalse(owner.readyFor("B"))
        } finally {
            gate.countDown(); owner.release()
            withContext(NonCancellable) { withTimeout(5000) { owner.awaitClosed() } }
        }
        assertEquals(1, a.releases)
    }
    @Test fun staleRetiredCallbackCannotRevokeReplacement() = runBlocking {
        val owner = PreparedPlayer(); val a = Backend(); val b = Backend()
        try {
            owner.prepare("A") { a }; owner.prepare("B") { b }; a.callback()
            assertTrue(owner.readyFor("B")); assertNull(owner.failure)
        } finally { owner.release(); withContext(NonCancellable) { withTimeout(5000) { owner.awaitClosed() } } }
        assertEquals(1, a.releases); assertEquals(1, b.releases)
    }
    @Test fun failedWaveformDoesNotPreventBackendBackedPlayback() = runBlocking {
        val owner = PreparedPlayer(); val b = Backend()
        try {
            val result = com.typezero.resound.feature.edit.PreviewLoad().load(1000, {},
                { error("decode") }, { owner.prepare("A") { b } }, { owner.readyFor("A") })!!
            assertTrue(result.ready); assertTrue(owner.readyFor("A")); assertEquals(1, b.prepares)
            assertEquals(WaveformProvenance.UNAVAILABLE, result.waveform!!.provenance)
            assertEquals("Waveform unavailable", result.message)
        } finally { owner.release(); withContext(NonCancellable) { withTimeout(5000) { owner.awaitClosed() } } }
    }
    @Test fun factoryBindAndAllocatorFailuresNeverInstallCandidate() = runBlocking {
        for (stage in listOf("bind", "allocate")) {
            val owner = PreparedPlayer(); var allocations = 0
            try {
                try { owner.prepareBound("A") { ticket ->
                    allocatePlayerBackend(ticket, PlayerBackendFactory {
                        if (stage == "bind") error("bind"); { it }
                    }) { allocations++; error("allocate") }
                }; fail("expected construction failure") } catch (_: IllegalStateException) {}
                assertEquals(if (stage == "bind") 0 else 1, allocations)
                assertFalse(owner.readyFor("A"))
            } finally { owner.release(); withContext(NonCancellable) { withTimeout(5000) { owner.awaitClosed() } } }
        }
    }
    @Test fun nativeAdapterConstructionFailureReleasesRawHandleOnce() {
        val raw = Any(); val primary = ConstructionFailure("adapter"); var releases = 0
        try { constructOwned(raw, { throw primary }, { assertSame(raw, it); releases++ }); fail("expected") }
        catch (t: ConstructionFailure) { assertSame(primary, t) }
        assertEquals(1, releases)
    }
    @Test fun variantDefaultIsPassThroughAndCreatesIndependentPlayers() = runBlocking {
        val owner = PreparedPlayer(); val backend = Backend()
        try {
            owner.prepareBound("content://ordinary/source") { ticket ->
                assertSame(backend, allocatePlayerBackend(ticket, PlayerRuntimeProvider.backendFactory()) { backend })
                backend
            }
            assertTrue(owner.readyFor("content://ordinary/source"))
            val children = mutableListOf<PreparedPlayer>()
            val factory = AudioPlayerFactory { AudioPlayer(PreparedPlayer().also { children += it }) }
            try { assertNotSame(factory.create(), factory.create()) }
            finally { children.forEach { it.release() }; withTimeout(5000) { children.forEach { it.awaitClosed() } } }
        } finally { owner.release(); withContext(NonCancellable) { withTimeout(5000) { owner.awaitClosed() } } }
    }
    @Test fun ticketsOriginateAtOwnerBeforeAllocation() = runBlocking {
        val a = PreparedPlayer(); val b = PreparedPlayer()
        val tickets = mutableListOf<PreparationTicket>()
        try {
            a.prepareBound("A") { ticket -> tickets += ticket; Backend() }
            a.prepareBound("A") { ticket -> tickets += ticket; Backend() }
            b.prepareBound("A") { ticket -> tickets += ticket; Backend() }
            assertEquals("A", tickets[0].sourceKey)
            assertSame(tickets[0].ownerIdentity, tickets[1].ownerIdentity)
            assertTrue(tickets[1].generation > tickets[0].generation)
            assertNotSame(tickets[0].ownerIdentity, tickets[2].ownerIdentity)
        } finally {
            a.release(); b.release()
            withContext(NonCancellable) { withTimeout(5000) { a.awaitClosed(); b.awaitClosed() } }
        }
    }
}
