package com.typezero.resound.feature.library

import com.typezero.resound.feature.settings.ReleaseChannel
import com.typezero.resound.feature.settings.releaseChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Host tests for Phase 4 Library/Settings presentation. Source checks are characterization, NOT rendering evidence. */
class LibrarySettingsPresentationTest {

    @Test fun libraryPhaseKeepsThePreRedesignPrecedence() {
        assertEquals(LibraryPhase.PERMISSION, libraryPhase(permissionGranted = false, loading = true, error = "x", count = 3))
        assertEquals(LibraryPhase.LOADING, libraryPhase(true, loading = true, error = "x", count = 3))
        assertEquals(LibraryPhase.ERROR, libraryPhase(true, false, "x", 3))
        assertEquals(LibraryPhase.EMPTY, libraryPhase(true, false, null, 0))
        assertEquals(LibraryPhase.LIST, libraryPhase(true, false, null, 1))
    }

    @Test fun subtitleCountsExports() {
        assertEquals("Exports in Music/Resound", librarySubtitle(0, "Music/Resound"))
        assertEquals("1 export · Music/Resound", librarySubtitle(1, "Music/Resound"))
        assertEquals("40 exports · Music/Resound", librarySubtitle(40, "Music/Resound"))
    }

    @Test fun formatBadgeComesFromTheExtension() {
        assertEquals("WAV", exportFormat("Take 1.wav"))
        assertEquals("FLAC", exportFormat("a.b.flac"))
        assertEquals("AUDIO", exportFormat("no_extension_export"))
        assertEquals("AUDIO", exportFormat("weird.extension-name"))
        assertEquals("AUDIO", exportFormat("trailing."))
    }

    @Test fun sizesAndDurationsAreUnchanged() {
        assertEquals("--:--", formatDuration(0))
        assertEquals("4:22", formatDuration(262_000))
        assertEquals("512 B", formatSize(512))
        assertEquals("1 KB", formatSize(1024))
        assertEquals("44.1 MB", formatSize(46_200_000))
        assertEquals("1.0 GB", formatSize(1_073_741_824))
    }

    @Test fun releaseChannelIsDerivedFromTheVersionGrammar() {
        assertEquals(ReleaseChannel.DEVELOPMENT, releaseChannel("0.8.0-dev.12"))
        assertEquals(ReleaseChannel.STABLE, releaseChannel("1.0"))
        assertEquals(ReleaseChannel.STABLE, releaseChannel("0.8.0"))
        assertNull(releaseChannel("—"))
        assertNull(releaseChannel(null))
        assertNull(releaseChannel("0.8.0-beta.1"))
        assertNull("malformed dev version shows no badge rather than a guess", releaseChannel("0.9.0-dev"))
        assertEquals("DEVELOPMENT", ReleaseChannel.DEVELOPMENT.label)
    }

    /** LibraryAudio needs android.net.Uri (a stub in host tests), so the row line is tested through its primitive overload. */
    @Test fun detailsLineOmitsTheExtensionAndHandlesUnknownDates() {
        assertEquals("4:22  ·  1.0 MB  ·  Unknown date", exportDetails(durationMs = 262_000, sizeBytes = 1_048_576, modifiedSeconds = 0))
        val dated = exportDetails(durationMs = 0, sizeBytes = 512, modifiedSeconds = 1_794_000_000)
        assertTrue(dated, dated.startsWith("--:--  ·  512 B  ·  ") && !dated.endsWith("Unknown date"))
        val s = source("feature/library/LibraryScreen.kt")
        assertTrue(s.contains("internal fun exportDetails(item: LibraryAudio): String = exportDetails(item.durationMs, item.sizeBytes, item.modifiedSeconds)"))
    }

    @Test fun libraryBehaviourIsWiredAsBefore() {
        val s = source("feature/library/LibraryScreen.kt")
        assertFalse("Scaffold already pads system bars", s.contains("safeDrawingPadding"))
        assertTrue(s.contains("LaunchedEffect(Unit) {\n        if (!permissionGranted) permissionLauncher.launch(audioPermission())"))
        assertTrue(s.contains("onAllowAccess = { permissionLauncher.launch(audioPermission()) }"))
        assertTrue(s.contains("onOpen = { item -> onOpenInEditor(item.uri) }"))
        assertTrue(s.contains("onShare = { item -> shareAudio(context, item) }"))
        assertTrue(s.contains("putExtra(Intent.EXTRA_STREAM, item.uri)") && s.contains("addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)"))
        assertTrue("refresh gating is on the refresh action",
            s.contains("ResoundIconAction(Icons.Outlined.Refresh, \"Refresh library\", onRefresh, enabled = !loading && permissionGranted)"))
        assertTrue(s.contains("LaunchedEffect(permissionGranted, refreshKey) {") && s.contains("runCatching { ResoundLibrary.load(context) }"))
        assertTrue(s.contains(".onFailure { error = it.message ?: \"Could not read ${'$'}{Outputs.musicDirectoryDisplay}\" }"))
    }

    @Test fun settingsBehaviourIsWiredAsBefore() {
        val s = source("feature/settings/SettingsScreen.kt")
        assertEquals("only the ReleaseChannel enum names the channel", 1, Regex("\"DEVELOPMENT\"").findAll(s).count())
        assertTrue(s.contains("channel = releaseChannel(version)"))
        assertFalse("debug-only treatment never referenced from main code", s.contains("SteelBlue"))
        assertTrue(s.contains("if (showAbout) AboutDialog(onDismiss = { showAbout = false })"))
        assertTrue(s.contains("if (showUpdater) UpdateDialog(onDismiss = { showUpdater = false })"))
        assertTrue(s.contains("\"https://github.com/MikereDD/Resound/blob/main/CHANGELOG.md\""))
        assertFalse("deprecated Divider", s.contains("material3.Divider"))
    }

    private fun source(path: String): String {
        val root = sequenceOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        return File(root, "com/typezero/resound/$path").readText().replace("\r\n", "\n")
    }

}
