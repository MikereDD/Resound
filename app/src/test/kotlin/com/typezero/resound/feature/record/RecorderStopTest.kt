package com.typezero.resound.feature.record

import org.junit.Assert.*
import org.junit.Test

class RecorderStopTest {
    @Test fun stopOkReleaseOk() {
        var released = 0
        assertTrue(stopAndRelease({}, { released++ })); assertEquals(1, released)
    }
    @Test fun stopFailureStillReleasesAndReportsFailure() {
        var released = 0
        assertFalse(stopAndRelease({ throw IllegalStateException("no data") }, { released++ })); assertEquals(1, released)
    }
    @Test fun releaseFailureAfterSuccessfulStopKeepsRecording() {
        assertTrue(stopAndRelease({}, { throw RuntimeException("release") }))
    }
    @Test fun releaseFailureAfterStopFailureDoesNotEscape() {
        assertFalse(stopAndRelease({ throw RuntimeException("s") }, { throw RuntimeException("r") }))
    }

    // Characterization of the platform-free outcome seam; never constructs MediaRecorder.
    @Test fun characterizationOutcomeBothSuccessAcknowledgesTermination() {
        val calls = mutableListOf<String>()
        val result = stopAndReleaseOutcome({ calls += "stop" }, { calls += "release" })
        assertEquals(listOf("stop", "release"), calls)
        assertTrue(result.stopped); assertTrue(result.released); assertNull(result.failure)
    }
    @Test fun characterizationSuccessfulStopWithReleaseFailureIsNotTermination() {
        val calls = mutableListOf<String>()
        val result = stopAndReleaseOutcome({ calls += "stop" }, {
            calls += "release"; throw IllegalStateException("synthetic-release")
        })
        assertEquals(listOf("stop", "release"), calls)
        assertTrue(result.stopped); assertFalse(result.released)
        assertEquals("synthetic-release", result.failure?.message)
        assertEquals(0, result.failure!!.suppressed.size)
    }
    @Test fun characterizationFailedStopKnownReleaseRetainsPrimaryDiagnostic() {
        val calls = mutableListOf<String>()
        val result = stopAndReleaseOutcome({
            calls += "stop"; throw IllegalStateException("synthetic-stop")
        }, { calls += "release" })
        assertEquals(listOf("stop", "release"), calls)
        assertFalse(result.stopped); assertTrue(result.released)
        assertEquals("synthetic-stop", result.failure?.message)
        assertEquals(0, result.failure!!.suppressed.size)
    }
    @Test fun characterizationBothFailuresKeepStopPrimaryAndReleaseSuppressed() {
        val calls = mutableListOf<String>()
        val result = stopAndReleaseOutcome({
            calls += "stop"; throw IllegalStateException("synthetic-stop-primary")
        }, {
            calls += "release"; throw IllegalArgumentException("synthetic-release-suppressed")
        })
        assertEquals(listOf("stop", "release"), calls)
        assertFalse(result.stopped); assertFalse(result.released)
        assertEquals("synthetic-stop-primary", result.failure?.message)
        assertEquals(listOf("synthetic-release-suppressed"), result.failure!!.suppressed.map { it.message })
        // Compare diagnostics, not throwable identity (coroutine recovery may copy exceptions).
    }
}
