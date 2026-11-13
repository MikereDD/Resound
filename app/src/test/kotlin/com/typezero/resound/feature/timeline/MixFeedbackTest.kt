/*
 * file:    MixFeedbackTest.kt
 * author:  Typezer∅
 * desc:    U1 regression guard: every Export Mix outcome reaches the viewport-visible snackbar, the mix rules
 *          (muted tracks excluded, unmuted unavailable references blocked) are unchanged, and the snackbar keeps
 *          the workstation palette, a dismiss action and a readable duration.
 */
package com.typezero.resound.feature.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MixFeedbackTest {
    private fun screen(): String {
        val root = sequenceOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        return File(root, "com/typezero/resound/feature/timeline/TimelineScreen.kt").readText()
    }

    private fun exportMixBody(): String = screen().substringAfter("fun exportMix() {").substringBefore("MultitrackWorkspace(")

    @Test fun everyOutcomeIsAnnouncedOnce() {
        val body = exportMixBody()
        // empty, blocked, success/rc failure, exception
        assertEquals(4, Regex("""announceMixOutcome\(""").findAll(body).count())
        assertFalse("no outcome bypasses the announcement", Regex("""status = (if \(result|"Mix (blocked|saved|failed)|"Add a clip)""").containsMatchIn(body))
        // progress stays a header-only status (not a notification)
        assertTrue(body.contains("status = \"Mixing tracks…\""))
    }

    @Test fun mixRulesAreUnchanged() {
        val body = exportMixBody()
        assertTrue(body.contains("if (track.muted) emptyList() else track.clips.map { track to it }"))
        assertTrue(body.contains("if (active.any { !it.second.sourceAvailable }) {"))
        assertTrue("blocked before any input is resolved",
            body.indexOf("sourceAvailable") < body.indexOf("AudioFiles.resolveToCache"))
        assertTrue(body.contains("Effects.mixTimeline(inputs, temp.absolutePath)"))
    }

    @Test fun announcementReplacesTheCurrentOneAndStaysReadable() {
        val s = screen()
        val announce = s.substringAfter("fun announceMixOutcome(").substringBefore("\n    }\n")
        assertTrue(announce.contains("status = message"))
        assertTrue(announce.contains("mixFeedback.currentSnackbarData?.dismiss()"))
        assertTrue(announce.contains("withDismissAction = true"))
        assertTrue(announce.contains("duration = SnackbarDuration.Long"))
    }

    @Test fun snackbarIsHostedOverTheWorkspaceInTheWorkstationPalette() {
        val s = screen()
        assertTrue(s.contains("SnackbarHost(mixFeedback, Modifier.align(Alignment.BottomCenter)"))
        val bar = s.substringAfter("private fun MixFeedbackSnackbar(")
        listOf("containerColor = PanelHi", "contentColor = TextHi", "actionColor = Accent", "BorderStroke(1.dp, Line)")
            .forEach { assertTrue("snackbar uses $it", bar.contains(it)) }
    }
}
