package com.typezero.resound.feature.edit

import com.typezero.resound.core.audio.Waveform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditorPresentationTest {

    @Test fun acceptedSourceShowsWorkspaceEvenWhenWaveformAndPlaybackAreUnavailable() = runBlocking {
        var hasSource = false
        var visibleDuringDecode = false
        val result = PreviewLoad().load(1_000,
            accept = { hasSource = true },
            decode = {
                visibleDuringDecode = showEditorWorkspace(hasSource)
                error("decode unavailable")
            },
            prepare = { error("playback unavailable") },
        )
        assertNotNull(result)
        assertFalse(result!!.ready)
        assertTrue(visibleDuringDecode)
        assertTrue(showEditorWorkspace(hasSource))
    }

    @Test fun rejectedSourceLeavesTheCompactEditorAvailable() = runBlocking {
        var hasSource = false
        val result = PreviewLoad().load(0,
            accept = { hasSource = true },
            decode = { error("invalid source must not decode") },
            prepare = { error("invalid source must not prepare") },
        )
        assertNull(result)
        assertFalse(showEditorWorkspace(hasSource))
    }

    @Test fun successfulCloseReturnsToCompactAndNextAcceptedSourceRestoresWorkspace() = runBlocking {
        var hasSource = true
        assertTrue(showEditorWorkspace(hasSource))
        assertTrue(closeEditorAudio(true, {}, {}, { hasSource = false }))
        assertFalse(showEditorWorkspace(hasSource))
        // Lower-level preview/presentation integration, not a device Stop/Reopen interaction.
        val result = PreviewLoad().load(1_000,
            accept = { hasSource = true },
            decode = { Waveform.unavailable(1_000) },
            prepare = {},
        )
        assertNotNull(result)
        assertTrue(showEditorWorkspace(hasSource))
    }

    @Test fun retirementFailureKeepsTheLoadedWorkspace() = runBlocking {
        var hasSource = true
        val failure = IllegalStateException("retirement uncertain")
        try {
            closeEditorAudio(true, {}, { throw failure }, { hasSource = false })
            fail("Close must propagate retirement failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertTrue(showEditorWorkspace(hasSource))
    }

    @Test fun emptyEditorDoesNotShowTheEditingWorkspace() {
        assertFalse(showEditorWorkspace(hasSource = false))
    }
}
