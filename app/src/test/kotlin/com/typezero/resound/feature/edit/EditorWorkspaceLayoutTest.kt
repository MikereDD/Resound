package com.typezero.resound.feature.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Host tests for the compact Editor layout. Source checks are characterization, NOT rendering evidence. */
class EditorWorkspaceLayoutTest {
    @Test fun flexHeightTakesTheLeftoverViewport() =
        assertEquals(300, viewportFlexHeight(viewportPx = 1000, usedPx = 700, flexCount = 1, minPx = 160, maxPx = 500))

    @Test fun flexHeightIsCappedAtTheMaximum() = assertEquals(500, viewportFlexHeight(2000, 700, 1, 160, 500))

    @Test fun negativeLeftoverKeepsTheMinimumSoTheColumnScrolls() =
        assertEquals(160, viewportFlexHeight(600, 900, 1, 160, 500))

    @Test fun minimumWinsWhenTheMaximumIsSmaller() = assertEquals(160, viewportFlexHeight(1000, 100, 1, 160, 120))

    @Test fun noFlexibleChildReceivesNoHeight() = assertEquals(0, viewportFlexHeight(1000, 100, 0, 160, 500))

    @Test fun optionsRequireNamingSupportAndOfferCloseOnlyWithAFile() {
        assertEquals(emptyList<EditorOptionEntry>(), editorOptionEntries(namingAvailable = false, showWorkspace = false, hasFile = false))
        assertEquals(listOf(EditorOptionEntry.RecordingName), editorOptionEntries(true, false, false))
        assertEquals(listOf(EditorOptionEntry.RecordingName, EditorOptionEntry.ExportName, EditorOptionEntry.CloseAudio),
            editorOptionEntries(true, true, true))
        assertEquals(listOf(EditorOptionEntry.CloseAudio), editorOptionEntries(false, true, true))
    }

    @Test fun dragMappingUsesTheCurrentWidth() {
        assertEquals(500L, waveformXToMs(100f, 200f, 1_000L))
        assertEquals(250L, waveformXToMs(100f, 400f, 1_000L)) // same finger position after rotation
        assertEquals(1_000L, waveformXToMs(900f, 400f, 1_000L))
        assertEquals(0L, waveformXToMs(-5f, 400f, 1_000L))
        assertEquals(0L, waveformXToMs(10f, 0f, 1_000L)) // before layout
        assertEquals(200f, waveformMsToX(500L, 400f, 1_000L), 0.001f)
    }

    private fun source(name: String): String {
        val root = sequenceOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        return File(root, "com/typezero/resound/feature/edit/$name").readText()
    }

    @Test fun workspaceComponentsAppearOnlyForALoadedSource() {
        val body = source("EditorWorkspace.kt").substringAfter("internal fun EditorWorkspace(")
            .substringBefore("options?.let { page ->")
        val entry = body.substringBefore("if (showWorkspace) {")
        val loaded = body.substringAfter("if (showWorkspace) {").substringBefore("} else {")
        listOf("WaveformWorkspace(", "TransportStrip(", "ToolGrid(", "OutputCard(").forEach {
            assertFalse("not before the workspace gate: $it", entry.contains(it))
            assertTrue("inside the workspace gate: $it", loaded.contains(it))
        }
        assertTrue(entry.indexOf("FileSourceCard(") in 0 until entry.indexOf("recoveryContent()"))
    }

    @Test fun layoutAndAccessibilityInvariants() {
        val s = source("EditorWorkspace.kt")
        val card = s.substringAfter("private fun FileSourceCard(").substringBefore("/** Tick values")
        assertTrue("visible Close audio", card.contains("\"Close audio\"") && card.contains("callbacks.onClose"))
        assertEquals(2, Regex("""heightIn\(min = 48\.dp\)""").findAll(card.substringAfter("if (state.file == null) {")).count())
        assertFalse(s.substringAfter("private fun StatusInline(").substringBefore("private data class ToolSpec").contains("maxLines"))
        assertTrue(s.contains("LocalLayoutDirection provides LayoutDirection.Ltr"))
        assertTrue(s.contains("coerceIn(constraints.minHeight, constraints.maxHeight)"))
        assertFalse("drag width must not be captured once", source("WaveformView.kt").contains("val w = size.width.toFloat()"))
    }
}
