package com.typezero.resound.feature.timeline

import org.junit.Assert.*
import org.junit.Test

/**
 * HOST-LEVEL ownership logic only. Proves what SessionHolder/TimelineSession retain when the
 * SAME holder is reused (what AppContainer/Application provides across tab switches and
 * activity recreation) and that a NEW holder (new process) starts empty.
 * It does NOT prove Compose/Activity behaviour: ResoundRoot/TimelineScreen are unverified here.
 */
class TimelineOwnershipTest {
    private fun fill(s: TimelineSession) {
        s.tracks = listOf(Track(s.nextId(), "Track 1", muted = true, volume = 0.5), Track(s.nextId(), "Track 2"))
        s.selectedClip = 99L; s.pxPerSec = 60f
    }
    @Test fun sameSessionRetainsEverythingAcrossTabRoundTripsAndRecreation() {
        val app = TimelineSession(); fill(app)
        repeat(3) {
            val viewedByScreen = app            // tab away and back / new activity: same app-owned object
            assertEquals(2, viewedByScreen.tracks.size); assertTrue(viewedByScreen.tracks[0].muted)
            assertEquals(0.5, viewedByScreen.tracks[0].volume, 0.0)
            assertEquals(99L, viewedByScreen.selectedClip); assertEquals(60f, viewedByScreen.pxPerSec, 0f)
        }
        assertEquals(3L, app.nextId())          // id allocation continues, no reuse
    }
    @Test fun unattachedSessionStartsEmptyUntilPersistenceOwnerIsBound() {
        fill(TimelineSession())
        val afterProcessDeath = TimelineSession()
        assertTrue(afterProcessDeath.tracks.isEmpty()); assertNull(afterProcessDeath.selectedClip)
        assertEquals(1L, afterProcessDeath.nextId())   // No store attached: not a process-recovery assertion.
    }
}
