package com.typezero.resound.feature.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure Editor decisions: Trim minimum (F10 equal endpoints) and microphone background policy (F11). */
class EditorPoliciesTest {
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
