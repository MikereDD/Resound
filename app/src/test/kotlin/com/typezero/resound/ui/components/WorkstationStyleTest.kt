package com.typezero.resound.ui.components

import com.typezero.resound.ui.theme.Accent
import com.typezero.resound.ui.theme.AccentLine
import com.typezero.resound.ui.theme.ControlIcon
import com.typezero.resound.ui.theme.ControlLabel
import com.typezero.resound.ui.theme.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Phase 4.5 control styling. Style resolution is pure; source checks are characterization, NOT render evidence. */
class WorkstationStyleTest {

    @Test fun primaryActionIsTheExportMixReference() {
        val s = actionStyle(ActionProminence.Primary, enabled = true, pressed = false, focused = false)
        assertEquals(AccentLine, s.border)
        assertEquals(1f, s.borderWidth.value, 0f)
        assertEquals(Accent, s.icon)
        assertEquals(ControlLabel, s.label)
        assertEquals(ControlIcon.Primary, s.iconSize)
        assertEquals("opaque black or near-black surface (no glow)", 1f, s.container.alpha, 0f)
    }

    @Test fun pressAndFocusAreShapeCuesNotOnlyColour() {
        for ((pressed, focused) in listOf(true to false, false to true)) {
            val s = actionStyle(ActionProminence.Primary, true, pressed, focused)
            assertEquals(2f, s.borderWidth.value, 0f)
            assertEquals(Accent, s.border)
        }
        val idle = actionStyle(ActionProminence.Primary, true, false, false)
        assertNotEquals(idle.container, actionStyle(ActionProminence.Primary, true, pressed = true, focused = false).container)
    }

    @Test fun disabledIsDistinguishable() {
        val enabled = actionStyle(ActionProminence.Primary, true, false, false)
        val disabled = actionStyle(ActionProminence.Primary, false, pressed = true, focused = true)
        assertEquals(Line, disabled.border)
        assertEquals(1f, disabled.borderWidth.value, 0f)
        assertNotEquals(enabled.icon, disabled.icon)
        assertNotEquals(enabled.label, disabled.label)
    }

    @Test fun secondaryKeepsHierarchy() {
        val s = actionStyle(ActionProminence.Secondary, true, false, false)
        assertEquals(Line, s.border)
        assertTrue(s.iconSize < ControlIcon.Primary)
    }

    @Test fun cyanIsDataOnlyAndGlowIsGone() {
        val main = sources()
        assertFalse(main.any { (_, text) -> text.contains("SignalGlow") })
        val signalUsers = main.filter { (_, text) -> Regex("""(?<![\w.])Signal(?![\w])""").containsMatchIn(text.lines().filterNot { it.startsWith("import ") }.joinToString("\n")) }
            .map { it.first }.toSet()
        assertEquals("cyan remains only for the waveform body and the first track identity (Theme declares it)",
            setOf("Theme.kt", "WaveformView.kt", "MultitrackWorkspace.kt"), signalUsers)
        val ws = main.first { it.first == "MultitrackWorkspace.kt" }.second
        assertEquals(1, Regex("""(?<![\w.])Signal(?![\w])""").findAll(ws.lines().filterNot { it.startsWith("import ") }.joinToString("\n")).count())
        assertTrue(ws.contains("0 -> Signal"))
    }

    @Test fun reviewFixesStayInPlace() {
        val ws = sources().first { it.first == "MultitrackWorkspace.kt" }.second
        assertTrue("disabled lane controls are dimmed, not Accent-alike grey",
            ws.contains("!enabled -> LaneControlColors(surface = PanelHi, border = Line, content = TextLo.copy(alpha = 0.45f))"))
        assertTrue("lane label follows the content colour (theme text styles carry a fixed colour)",
            ws.contains("Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = LocalContentColor.current,"))
        val editor = sources().first { it.first == "EditorWorkspace.kt" }.second
        assertTrue("options label follows the button content colour",
            editor.contains("Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current)"))
        assertTrue("toolbar row decision and zoom label use the widest label, not the current zoom",
            ws.contains("private const val WIDEST_ZOOM_LABEL = \"240 px/s\"") && ws.contains("val oneRow = toolbarFitsOneRow()") &&
                ws.contains("modifier = Modifier.width(zoomLabelWidth()),") && ws.contains(".coerceAtMost(240f)"))
        val ew = sources().first { it.first == "EditorWorkspace.kt" }.second
        assertEquals("text-only outlined buttons use light-grey labels", 6, Regex("colors = resoundOutlinedTextColors\\(\\)").findAll(ew).count())
    }

    @Test fun exportMixKeepsItsBehaviourWiring() {
        val ws = sources().first { it.first == "MultitrackWorkspace.kt" }.second
        assertTrue(ws.contains("ExportMixButton(exportEnabled, callbacks.onExportMix"))
        assertTrue(ws.contains("ResoundAction(\"Export Mix\", Icons.Outlined.FileUpload, onClick, modifier, enabled = enabled,"))
    }

    /** Main sources with comment lines removed, so a comment mentioning a token cannot trip the checks. */
    private fun sources(): List<Pair<String, String>> {
        val root = sequenceOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        return root.walkTopDown().filter { it.extension == "kt" }.map { file ->
            file.name to file.readText().replace("\r\n", "\n").lines()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
                .joinToString("\n")
        }.toList()
    }
}
