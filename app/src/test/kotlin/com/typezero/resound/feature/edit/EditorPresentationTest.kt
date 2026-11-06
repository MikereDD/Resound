package com.typezero.resound.feature.edit

import com.typezero.resound.core.audio.Waveform
import kotlinx.coroutines.runBlocking
import java.io.File
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

    private fun editorSource(): String {
        val root = sequenceOf(File("src/main/kotlin"), File("app/src/main/kotlin"))
            .first { it.isDirectory }
        return File(root, "com/typezero/resound/feature/edit/EditScreen.kt").readText()
    }

    /** Source characterization, NOT Compose rendering or device navigation evidence. */
    @Test fun actualLayoutKeepsEntryCardsOutsideTheLoadedOnlyWorkspace() {
        val source = editorSource()
        assertTrue(source.contains("val showWorkspace = showEditorWorkspace(current != null)"))
        val layout = source.substringAfter("            EditorHeader()")
            .substringBefore("    val op = pendingOp")
        val entry = layout.substringBefore("            if (showWorkspace) {")
        val workspace = layout.substringAfter("            if (showWorkspace) {")
        listOf("FileSourceCard(", "NamingPanel(\"Recording name\"", "RecentRecordingCard(",
            "DraftRecoveryCard(", "StatusBanner(", "Output: \${Outputs.musicDirectoryDisplay}").forEach {
            assertTrue("entry component $it", entry.contains(it))
        }
        listOf("WaveformWorkspace(", "TransportStrip(", "ToolGrid(",
            "NamingPanel(\"Export new copy name\"", "OutputCard(").forEach {
            assertFalse("not in empty layout: $it", entry.contains(it))
            assertTrue("loaded component $it", workspace.contains(it))
        }
        assertTrue(entry.indexOf("FileSourceCard(") < entry.indexOf("NamingPanel(\"Recording name\""))
    }

    /** Source characterization, NOT measured font-scale or accessibility/touch execution. */
    @Test fun compactPrimaryActionsGrowVerticallyAndStatusDoesNotTruncate() {
        val source = editorSource()
        val actions = source.substringAfter("            if (file == null) {")
            .substringBefore("            } else {")
        assertEquals(2, Regex("heightIn\\(min = 56\\.dp\\)").findAll(actions).count())
        assertTrue(actions.contains("Text(\"Open audio\")"))
        assertTrue(actions.contains("else \"Record\""))
        assertFalse(actions.contains(".weight("))
        assertFalse(actions.contains(".height("))
        assertTrue(source.contains("maxLines = if (file == null) Int.MAX_VALUE else 1"))
        val status = source.substringAfter("private fun StatusBanner(").substringBefore("private data class ToolSpec")
        assertTrue(status.contains("MaterialTheme.typography.bodyMedium"))
        assertFalse(status.contains("maxLines"))
    }

    @Test fun emptyEditorDoesNotShowTheEditingWorkspace() {
        assertFalse(showEditorWorkspace(hasSource = false))
    }
}
