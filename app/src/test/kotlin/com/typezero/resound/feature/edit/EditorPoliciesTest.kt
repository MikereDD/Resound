package com.typezero.resound.feature.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.*
import com.typezero.resound.core.audio.PreparedPlayer
import com.typezero.resound.core.audio.PlayerBackend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

/** Editor policies, Close retirement, Library admission, Trim minimum and microphone background policy. */
class EditorPoliciesTest {
    @Test fun closeRefusesEveryBusyOrEmptyCombinationWithoutTouchingPlayback() = runBlocking {
        for (mask in 0..31) {
            val hasSource = mask and 1 != 0
            val recording = mask and 2 != 0
            val starting = mask and 4 != 0
            val recorderBusy = mask and 8 != 0
            val busy = mask and 16 != 0
            val allowed = hasSource && !recording && !starting && !recorderBusy && !busy
            val admitted = canCloseEditorAudio(hasSource, recording, starting, recorderBusy, busy)
            assertEquals("combination $mask", allowed, admitted)
            var calls = 0
            assertEquals(allowed, closeEditorAudio(admitted, { calls++ }, { calls++ }, { calls++ }))
            assertEquals(if (allowed) 3 else 0, calls)
        }
    }

    @Test fun failedOrCancelledRetirementDoesNotClearTheLoadedSource() = runBlocking {
        for (failure in listOf(IllegalStateException("retirement timeout"), CancellationException("left Editor"))) {
            var source: String? = "published.m4a"
            var waveform: String? = "decoded waveform"
            var selection = 42L
            try {
                closeEditorAudio(true, {}, { throw failure }, {
                    source = null; waveform = null; selection = 0L
                })
                error("retirement failure was swallowed")
            } catch (actual: Exception) { assertTrue(actual === failure) }
            assertEquals("published.m4a", source)
            assertEquals("decoded waveform", waveform)
            assertEquals(42L, selection)
        }
    }

    @Test fun cancelledCallerCannotClearEvenIfRetirementReturnsNormally() = runBlocking {
        var cleared = false
        val caller = Job()
        try {
            withContext(caller) {
                closeEditorAudio(true, {}, { currentCoroutineContext().cancel() }, { cleared = true })
            }
            error("cancelled caller returned success")
        } catch (_: CancellationException) { /* expected */ }
        finally { caller.cancel() }
        assertFalse(cleared)
    }

    private open class CloseBackend : PlayerBackend {
        var releases = 0
        override fun prepare() {}
        override fun start() {}
        override fun pause() { error("Close must not pause a potentially unstarted native player") }
        override fun seekTo(ms: Long) {}
        override fun release() { releases++ }
        override val isPlaying = false
        override val currentMs = 0L
    }

    @Test fun closeWaitsForTheRealOwnedReleaseWithoutBlockingTheCallerDispatcher() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CountDownLatch(1)
        val backend = object : CloseBackend() {
            override fun release() {
                entered.complete(Unit)
                check(resume.await(10, TimeUnit.SECONDS))
                super.release()
            }
        }
        val owner = PreparedPlayer()
        var source: String? = "recording.m4a"
        try {
            owner.prepare(source!!) { backend }
            val closing = async(start = CoroutineStart.UNDISPATCHED) {
                closeEditorAudio(true, { owner.invalidate() }, { owner.awaitRetirement() }, { source = null })
            }
            try {
                withTimeout(5_000) { entered.await() }
                assertFalse(owner.readyFor("recording.m4a"))
                assertFalse(closing.isCompleted)
                assertEquals("recording.m4a", source)
                // Another coroutine on this dispatcher runs while the native release is blocked.
                assertEquals("responsive", withTimeout(5_000) { async { "responsive" }.await() })
                resume.countDown()
                assertTrue(withTimeout(5_000) { closing.await() })
                assertEquals(null, source)
                assertEquals(1, backend.releases)
                // Retirement did not dispose the owner; Open/Recent can prepare the next source.
                owner.prepare("reopened.m4a") { CloseBackend() }
                assertTrue(owner.readyFor("reopened.m4a"))
            } finally { resume.countDown(); closing.cancelAndJoin() }
        } finally {
            resume.countDown()
            owner.release()
            withTimeout(10_000) { owner.awaitClosed() }
        }
    }

    @Test fun realNativeReleaseFailureKeepsTheSourceButRevokesPlayback() = runBlocking {
        val owner = PreparedPlayer()
        var cleared = false
        try {
            owner.prepare("recording.m4a") { object : CloseBackend() {
                override fun release() { super.release(); error("native release failed") }
            } }
            try {
                closeEditorAudio(true, { owner.invalidate() }, { owner.awaitRetirement() }, { cleared = true })
                error("native release failure was swallowed")
            } catch (_: IllegalStateException) { /* existing owner fails closed */ }
            assertFalse(cleared)
            assertFalse(owner.readyFor("recording.m4a"))
        } finally { owner.release(); withTimeout(10_000) { owner.awaitClosed() } }
    }

    @Test fun declinedOrThrowingLibraryAdmissionKeepsThePendingRequest() {
        val chosen = EditorOpenRequest(1L, "content://recordings/1")
        var pending: EditorOpenRequest? = chosen
        var acknowledgements = 0
        val acknowledge: (Long) -> Unit = {
            acknowledgements++; pending = consumeEditorOpenRequest(pending, it)
        }
        assertFalse(acceptEditorOpenRequest(pending, { false }, acknowledge))
        assertEquals(chosen, pending)
        try {
            acceptEditorOpenRequest(pending, { error("admission failed") }, acknowledge)
            error("admission failure was swallowed")
        } catch (_: IllegalStateException) { /* no acknowledgement */ }
        assertEquals(chosen, pending)
        assertEquals(0, acknowledgements)
        assertTrue(acceptEditorOpenRequest(pending, { true }, acknowledge))
        assertEquals(null, pending)
        assertEquals(1, acknowledgements)
    }

    @Test fun staleAcknowledgementCannotConsumeANewerSameUriLibrarySelection() {
        val old = EditorOpenRequest(1L, "content://recordings/1")
        val next = EditorOpenRequest(2L, old.uri)
        assertEquals(next, consumeEditorOpenRequest(next, old.id))
        assertEquals(null, consumeEditorOpenRequest(next, next.id))
        assertEquals(null, consumeEditorOpenRequest(null, old.id))
        // Reopening the same file explicitly is allowed after Close; it is a new request.
        var accepted: Long? = null
        assertTrue(acceptEditorOpenRequest(next, { true }, { accepted = it }))
        assertEquals(next.id, accepted)
    }

    @Test fun libraryRequestIsConsumedAtAdmissionAndDoesNotReplayAfterClose() {
        val chosen = EditorOpenRequest(1L, "content://media/external/audio/media/1")
        var pending: EditorOpenRequest? = chosen
        var loads = 0
        assertTrue(acceptEditorOpenRequest(pending,
            admit = { assertEquals(chosen.uri, it); loads++; true },
            acknowledge = { pending = consumeEditorOpenRequest(pending, it) }))
        assertEquals(null, pending)
        // Closing the loaded audio then returning to Editor must not replay accepted work.
        assertFalse(acceptEditorOpenRequest(pending, { loads++; true }, { error("unexpected acknowledgement") }))
        assertEquals(1, loads)
    }

    @Test fun closeAudioRetiresBeforeClearingAndOnlyWhenAdmitted() = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<String>()
        var source: String? = "recording.m4a"
        val result = closeEditorAudio(
            admitted = canCloseEditorAudio(true, false, false, false, false),
            revoke = { events += "revoke" },
            awaitRetirement = { events += "retired" },
            clear = { events += "clear"; source = null },
        )
        assertTrue(result)
        assertEquals(listOf("revoke", "retired", "clear"), events)
        assertEquals(null, source)
    }

    @Test fun equalOrTooShortTrimSelectionsAreRefusedBeforeEncoding() {
        assertFalse(isTrimSelectionValid(5_000, 5_000, 10_000))
        assertFalse(isTrimSelectionValid(5_000, 5_000 + MIN_TRIM_SELECTION_MS - 1, 10_000))
        assertFalse(isTrimSelectionValid(6_000, 5_000, 10_000))
        assertFalse(isTrimSelectionValid(0, 0, 0))
    }

    @Test fun trimSelectionMustStayInsideTheSource() {
        assertFalse(isTrimSelectionValid(-1, 1_000, 10_000))
        assertFalse(isTrimSelectionValid(9_000, 10_001, 10_000))
        assertTrue(isTrimSelectionValid(0, 10_000, 10_000))
        assertTrue(isTrimSelectionValid(5_000, 5_000 + MIN_TRIM_SELECTION_MS, 10_000))
    }

    @Test fun leavingTheForegroundStopsActiveCaptureOnce() {
        assertEquals(BackgroundRecordingAction.STOP_NOW,
            backgroundRecordingAction(recording = true, starting = false, recorderBusy = false))
    }

    @Test fun startInFlightIsStoppedAfterItCompletes() {
        assertEquals(BackgroundRecordingAction.STOP_AFTER_START,
            backgroundRecordingAction(recording = false, starting = true, recorderBusy = true))
    }

    @Test fun stopOrPostStopPublicationInFlightIsNotStoppedAgain() {
        // Stop in flight: still recording, busy.
        assertEquals(BackgroundRecordingAction.NONE,
            backgroundRecordingAction(recording = true, starting = false, recorderBusy = true))
        // After stop returned: not recording, busy while publishing. Must not arm a deferred stop
        // that would end the user's NEXT recording.
        assertEquals(BackgroundRecordingAction.NONE,
            backgroundRecordingAction(recording = false, starting = false, recorderBusy = true))
        assertEquals(BackgroundRecordingAction.NONE,
            backgroundRecordingAction(recording = false, starting = false, recorderBusy = false))
    }
}
