package com.typezero.resound.feature.timeline

import com.typezero.resound.ui.components.navLabelLines
import com.typezero.resound.ui.theme.Accent
import com.typezero.resound.ui.theme.Amber
import com.typezero.resound.ui.theme.Ink
import com.typezero.resound.ui.theme.Line
import com.typezero.resound.ui.theme.PanelHi
import com.typezero.resound.ui.theme.TextLo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Host tests for the Multitrack layout. Source checks are characterization, NOT rendering evidence. */
class MultitrackWorkspaceLayoutTest {
    @Test fun laneControlDisabledTakesPrecedenceOverMuted() {
        val disabled = LaneControlColors(surface = PanelHi, border = Line, content = TextLo.copy(alpha = 0.45f))
        assertEquals("enabled + muted keeps amber", LaneControlColors(Amber, Amber, Ink), laneControlColors(active = true, enabled = true))
        assertEquals("enabled + unmuted is normal", LaneControlColors(PanelHi, Line, Accent), laneControlColors(active = false, enabled = true))
        assertEquals("disabled + muted is disabled, not amber", disabled, laneControlColors(active = true, enabled = false))
        assertEquals("disabled + unmuted is disabled", disabled, laneControlColors(active = false, enabled = false))
        // busy or pending recovery is what disables the lanes
        assertEquals(disabled, laneControlColors(active = true, enabled = multitrackEditingEnabled(recoveryReady = true, busy = true)))
        assertEquals(disabled, laneControlColors(active = true, enabled = multitrackEditingEnabled(recoveryReady = false, busy = false)))
        val ws = source("MultitrackWorkspace.kt")
        assertTrue("LaneControl draws surface, border and content from laneControlColors",
            ws.contains("val colors = laneControlColors(active, enabled)") && ws.contains("color = colors.surface,") &&
                ws.contains("contentColor = colors.content,") && ws.contains("border = BorderStroke(1.dp, colors.border),"))
        assertTrue("mute state and action stay wired",
            ws.contains("active = track.muted, enabled = enabled) { callbacks.onToggleMute(track.id) }"))
    }

    @Test fun editingRequiresRecoveryAndIdle() {
        assertTrue(multitrackEditingEnabled(recoveryReady = true, busy = false))
        assertFalse(multitrackEditingEnabled(recoveryReady = false, busy = false))
        assertFalse(multitrackEditingEnabled(recoveryReady = true, busy = true))
    }

    @Test fun exportNeedsAClipAndEditing() {
        val empty = listOf(Track(1, "Track 1"))
        assertFalse(exportMixEnabled(true, empty))
        assertFalse(exportMixEnabled(true, emptyList<Track>()))
    }

    /** Clip construction needs android.net.Uri (unavailable in plain JVM tests), so the rule is tested by count. */
    @Test fun exportIsEnabledWithAClipWhileEditing() {
        assertTrue(exportMixEnabled(editingEnabled = true, clipCount = 1))
        assertTrue(exportMixEnabled(editingEnabled = true, clipCount = 3))
        assertFalse(exportMixEnabled(editingEnabled = false, clipCount = 3))
        assertFalse(exportMixEnabled(editingEnabled = true, clipCount = 0))
        val ws = source("MultitrackWorkspace.kt")
        assertTrue(ws.contains("exportMixEnabled(editingEnabled, tracks.sumOf { it.clips.size })"))
        assertTrue(ws.contains("val exportEnabled = exportMixEnabled(enabled, state.tracks)"))
        assertTrue(ws.contains("ExportMixButton(exportEnabled, callbacks.onExportMix"))
    }

    @Test fun zoomStepsAndBoundsMatchThePreviousControls() {
        assertEquals(24f / 1.35f, zoomedOut(24f), 0.0001f)
        assertEquals(24f * 1.35f, zoomedIn(24f), 0.0001f)
        assertEquals(6f, zoomedOut(7f), 0f)
        assertEquals(240f, zoomedIn(200f), 0f)
    }

    @Test fun rulerLabelsNeverCollide() {
        assertEquals(2, rulerLabelStepSeconds(pxPerSec = 24f, minSpacingPx = 40f))
        assertEquals(10, rulerLabelStepSeconds(6f, 40f))
        assertEquals(1, rulerLabelStepSeconds(120f, 40f))
        listOf(6f, 8.9f, 24f, 32.4f, 100f, 240f).forEach { zoom ->
            assertTrue(rulerLabelStepSeconds(zoom, 45f) * zoom >= 45f)
        }
    }

    @Test fun rulerLabelsAtTheContentEdgeAreSkippedNotGivenNegativeWidth() {
        assertEquals(97f, rulerLabelRoomPx(canvasWidthPx = 720f, labelLeftPx = 623f)!!, 0f)
        assertEquals(null, rulerLabelRoomPx(720f, 723f))   // last label at the content end (crash case)
        assertEquals(null, rulerLabelRoomPx(720f, 719.5f))
    }

    @Test fun displayDurationIsAtLeastThirtySeconds() = assertEquals(30_000L, timelineDisplayDurationMs(emptyList()))

    @Test fun timelineTakesTheLeftoverViewportButKeepsItsMinimum() {
        assertEquals(500, timelineFlexHeight(viewportPx = 900, usedPx = 400, minPx = 216))
        assertEquals(216, timelineFlexHeight(viewportPx = 500, usedPx = 400, minPx = 216))
    }

    @Test fun summaryPluralises() {
        assertEquals("0 tracks · 0 clips", multitrackSummary(emptyList()))
        assertEquals("1 track · 0 clips", multitrackSummary(listOf(Track(1, "Track 1"))))
    }

    @Test fun navigationLabelsSplitAtWordBoundariesNotMidWord() {
        assertEquals("Multi-\ntrack", navLabelLines("Multitrack"))
        listOf("Editor", "Multitrack", "Library", "Settings").forEach { label ->
            val lines = navLabelLines(label).split("\n")
            assertEquals(2, lines.size)
            assertEquals(label, lines[0].removeSuffix("-") + lines[1])
        }
    }

    private fun source(name: String): String {
        val root = sequenceOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        return File(root, "com/typezero/resound/feature/timeline/$name").readText()
    }

    @Test fun controllerKeepsArrangementImportAndMixdownLogic() {
        val screen = source("TimelineScreen.kt")
        listOf("MultitrackWorkspace(", "Effects.mixTimeline", "TrimMath.trimIn", "TrimMath.trimOut",
            "takePersistableUriPermission", "publishNamedToMusic", "if (editingEnabled) selectedClip = it",
            "it.copy(muted = !it.muted)", "zoomedOut(pxPerSec)", "zoomedIn(pxPerSec)").forEach {
            assertTrue("controller still contains $it", screen.contains(it))
        }
    }

    @Test fun clipGesturesAndSharedScrollingArePreserved() {
        val ws = source("MultitrackWorkspace.kt")
        val clip = ws.substringAfter("private fun ClipBox(").substringBefore("private fun trackAccent(")
        listOf("detectHorizontalDragGestures", "position.x < edge -> 1", "position.x > size.width - edge -> 2",
            "trimInCallback.value(delta)", "trimOutCallback.value(delta)", "moveCallback.value(delta)",
            "selectCallback.value()").forEach { assertTrue("clip gesture keeps $it", clip.contains(it)) }
        assertTrue(Regex("""horizontalScroll\((scrollState|horizontalScroll)\)""").findAll(ws).count() >= 2)
        assertTrue(ws.contains("LocalLayoutDirection provides LayoutDirection.Ltr"))
        assertTrue("ruler and lanes share one origin", ws.contains("(maxWidth - LANE_START)") &&
            ws.contains("Modifier.width(TRACK_HEADER_WIDTH)") && ws.contains("Box(Modifier.width(1.dp).fillMaxHeight().background(Line))"))
        assertFalse("ruler labels are drawn, not one Text per tick", ws.substringAfter("private fun TimelineRuler(")
            .substringBefore("private fun EmptyTimeline(").contains("absoluteOffset"))
        assertFalse("no decorative unimplemented actions", Regex("\"(Split|Duplicate|Delete|Solo|Undo|Redo)\"").containsMatchIn(ws))
    }
}
