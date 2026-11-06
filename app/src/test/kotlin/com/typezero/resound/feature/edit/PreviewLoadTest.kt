package com.typezero.resound.feature.edit

import com.typezero.resound.core.audio.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreviewLoadTest {
    private class RetiringBackend(private val failCleanup: Boolean = false) : PlayerBackend {
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        var releases = 0
        override fun prepare() {}
        override fun start() {}
        override fun pause() { error("retirement must not pause") }
        override fun release() {
            entered.countDown(); check(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)); releases++
            if (failCleanup) error("release failed")
        }
        override fun seekTo(ms: Long) {}
        override val isPlaying = true
        override val currentMs = 0L
    }
    @Test fun retirementBudgetDoesNotAdoptOrPrepareBUnderStalledA() = runBlocking {
        val expire = CompletableDeferred<Unit>()
        val awaiting = CompletableDeferred<Unit>()
        val owner = PreparedPlayer(awaitPolicy = { completion ->
            if (!completion.isCompleted) {
                awaiting.complete(Unit); expire.await(); error("retirement budget exhausted")
            }
            completion.await()
        })
        val a = RetiringBackend()
        owner.prepare("A") { a }; owner.play("A", null)
        var displayed = "A"
        var createdB = false
        var fault: Exception? = null
        val load = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                PreviewLoad().load(100, { owner.invalidate(); owner.awaitRetirement(); displayed = "B" },
                    { Waveform.unavailable(100) }, { owner.prepare("B") { createdB = true; a } })
            } catch (t: Exception) { fault = t }
        }
        try {
            awaiting.await(); check(a.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            expire.complete(Unit); load.join()
            assertEquals("retirement budget exhausted", fault?.message)
            assertEquals("A", displayed); assertFalse(createdB); assertEquals(0, a.releases)
            assertFalse(owner.readyFor("A")); assertFalse(owner.readyFor("B"))
            try { owner.prepare("B") { createdB = true; a }; fail("stalled retirement bypassed") }
            catch (_: IllegalStateException) {}
            assertFalse(createdB)
        } finally { a.resume.countDown(); owner.release(); withTimeout(10_000) { owner.awaitClosed() } }
        assertEquals(1, a.releases)
    }
    @Test fun cancellingSourceAdoptionDoesNotAbandonBlockedRetirement() = runBlocking {
        val owner = PreparedPlayer()
        val a = RetiringBackend()
        owner.prepare("A") { a }
        var displayed = "A"
        val load = launch(start = CoroutineStart.UNDISPATCHED) {
            PreviewLoad().load(100, { owner.invalidate(); owner.awaitRetirement(); displayed = "B" },
                { error("cancelled decode") }, { error("cancelled prepare") })
        }
        try {
            check(a.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            load.cancelAndJoin(); owner.release()
            assertEquals("A", displayed); assertEquals(0, a.releases)
            assertFalse(owner.readyFor("A"))
        } finally { a.resume.countDown(); owner.release(); withTimeout(10_000) { owner.awaitClosed() } }
        assertEquals(1, a.releases)
    }
    @Test fun failedNativeRetirementKeepsAIdentityAndRejectsB() = runBlocking {
        val owner = PreparedPlayer()
        val a = RetiringBackend(failCleanup = true)
        a.resume.countDown()
        owner.prepare("A") { a }
        var displayed = "A"
        try {
            PreviewLoad().load(100, { owner.invalidate(); owner.awaitRetirement(); displayed = "B" },
                { error("decode must not run") }, { error("prepare must not run") })
            fail("cleanup fault hidden")
        } catch (t: IllegalStateException) { assertEquals("Player cleanup failed", t.message) }
        finally { owner.release(); owner.awaitClosed() }
        assertEquals("A", displayed); assertFalse(owner.readyFor("A")); assertEquals(1, a.releases)
    }
    @Test fun acceptedMetadataWaitsForPlayingSourceRetirementOffCaller() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val caller = Thread.currentThread()
        var cleanupThread: Thread? = null
        val owner = PreparedPlayer()
        val a = object : PlayerBackend {
            override fun prepare() {}
            override fun start() {}
            override fun pause() { error("retirement must not pause") }
            override fun release() { cleanupThread = Thread.currentThread(); entered.countDown(); check(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            override fun seekTo(ms: Long) {}
            override val isPlaying = true
            override val currentMs = 0L
        }
        owner.prepare("A") { a }; owner.play("A", null)
        var displayed = "A"
        var preparedB = false
        val load = launch(start = CoroutineStart.UNDISPATCHED) {
            PreviewLoad().load(100, { owner.invalidate(); owner.awaitRetirement(); displayed = "B" },
                { Waveform.unavailable(100) }, {
                    owner.prepare("B") { object : PlayerBackend by a {
                        override fun prepare() { preparedB = true }
                        override fun pause() {}
                        override fun release() {}
                    } }
                })
        }
        try {
            check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("A must remain displayed until native retirement completes", "A", displayed)
            assertFalse(preparedB)
            assertNotSame(caller, cleanupThread)
        } finally { resume.countDown(); load.join(); owner.release(); owner.awaitClosed() }
        assertEquals("B", displayed); assertTrue(preparedB)
    }
    @Test fun preparationReturnDoesNotOverrideLostSourceReadiness() = runBlocking {
        val wave = Waveform(floatArrayOf(-.2f), floatArrayOf(.5f), 100)
        val result = PreviewLoad().load(100, {}, { wave }, {}, isReady = { false })!!
        assertFalse(result.ready); assertEquals("Playback unavailable", result.message)
    }
    @Test fun realWaveformIsPublishedBeforeFatalPreparationFailure() = runBlocking {
        val wave = Waveform(floatArrayOf(-.2f), floatArrayOf(.5f), 100)
        var displayedWave: Waveform? = null
        val original = AssertionError("native prepare fatal")
        try {
            PreviewLoad().load(100, {}, { wave }, { throw original }, onWaveform = { displayedWave = it })
            fail("fatal error must propagate")
        } catch (t: AssertionError) { assertSame(original, t) }
        assertSame("valid waveform must remain adopted independently", wave, displayedWave)
    }
    @Test fun acceptedBReleasesPlayingAAndKeepsBWaveformAfterPrepareFailure() = runBlocking {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        fun backend(id: String, fails: Boolean = false) = object : PlayerBackend {
            override fun prepare() { events += "prepare$id"; if (fails) error("prepare$id") }
            override fun start() { events += "play$id" }
            override fun pause() { events += "pause$id" }
            override fun seekTo(ms: Long) { events += "seek$id" }
            override fun release() { events += "release$id" }
            override val isPlaying = true
            override val currentMs = 0L
        }
        val owner = PreparedPlayer(Dispatchers.Unconfined)
        try {
            owner.prepare("content://A") { backend("A") }; owner.play("content://A", null)
            var displayed = "content://A"
            val waveB = Waveform(floatArrayOf(-.2f), floatArrayOf(.5f), 100)
            val loader = PreviewLoad()
            val rejected = loader.load(0, { error("invalid adoption") }, { error("decode") }, { error("prepare") })
            assertNull(rejected); assertTrue(owner.readyFor(displayed))
            val result = loader.load(100, { owner.invalidate(); owner.awaitRetirement(); displayed = "content://B" }, { waveB }, {
                owner.prepare(displayed) { backend("B", true) }
            })!!
            assertSame(waveB, result.waveform); assertFalse(result.ready)
            assertEquals("content://B", displayed); assertFalse(owner.readyFor(displayed))
            assertFalse("retirement must release directly, without an invalid pause", events.contains("pauseA"))
            assertTrue(events.indexOf("releaseA") < events.indexOf("prepareB"))
            try { owner.play(displayed, 0); fail("unready play") } catch (_: IllegalStateException) {}
            assertEquals(1, events.count { it == "playA" }); assertFalse(events.contains("playB"))
        } finally {
            withContext(NonCancellable) {
                owner.release()
                withTimeout(10_000) { owner.awaitClosed() }
            }
        }
    }
    @Test fun cancelledPrepareCannotPublishReadyResult() = runBlocking {
        var published = false
        val job = launch {
            PreviewLoad().load(100, {}, { Waveform(floatArrayOf(0f), floatArrayOf(0f), 100) }, {
                currentCoroutineContext().cancel()
            })
            published = true
        }
        job.join()
        assertFalse("cancelled preparation republished ready", published)
    }
    @Test fun decodeFailureDoesNotDisableIndependentPlayer() = runBlocking {
        var prepared = false
        val result = PreviewLoad().load(100, {}, { error("decode") }, { prepared = true })!!
        assertTrue(prepared); assertTrue(result.ready)
        assertEquals(WaveformProvenance.UNAVAILABLE, result.waveform!!.provenance)
        assertEquals("Waveform unavailable", result.message)
    }
    @Test fun prepareFailureKeepsRealWaveformButNotReadiness() = runBlocking {
        val wave = Waveform(floatArrayOf(-.5f), floatArrayOf(.8f), 100)
        var displayed = "A"
        val result = PreviewLoad().load(100, { displayed = "B" }, { wave }, { error("prepare") })!!
        assertEquals("B", displayed); assertSame(wave, result.waveform)
        assertFalse("must not report ready", result.ready)
        assertEquals("Playback unavailable", result.message)
    }
    @Test fun rejectedDurationPreservesPriorSession() = runBlocking {
        var displayed = "A"
        for (duration in listOf(0L, -1L)) {
            assertNull(PreviewLoad().load(duration, { displayed = "B" }, { error("decode called") }, { error("prepare called") }))
            assertEquals("A", displayed)
        }
    }
}
