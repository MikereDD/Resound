package com.typezero.resound.feature.library

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.typezero.resound.feature.edit.EditorPreviewEvents
import com.typezero.resound.ui.components.ResoundBottomBar
import com.typezero.resound.ui.components.ResoundDestination
import com.typezero.resound.ui.theme.ResoundTheme

/** Debug-only visual fixtures for the Library: no MediaStore, permission request or share sheet. */
private const val FOLDER = "Music/Resound"
private const val MODIFIED = 1_794_000_000L

private fun audio(i: Int, name: String, sizeBytes: Long, durationMs: Long, modified: Long = MODIFIED - i * 86_400L) =
    LibraryAudio(Uri.parse("content://resound.ui.fixture/library/$i"), name, "audio/wav", sizeBytes, durationMs, modified)

private val few = listOf(
    audio(1, "Midnight Drive (master).wav", 46_200_000, 262_000),
    audio(2, "Voice memo — trimmed.m4a", 1_310_000, 81_000),
    audio(3, "Drum loop 120bpm.mp3", 3_900_000, 32_000),
    audio(4, "Field recording 2026-11-07.flac", 220_400_000, 1_312_000),
    audio(5, "Ringtone.ogg", 412_000, 18_500, modified = 0),
)

private val many = (1..40).map { i ->
    val ext = listOf("wav", "mp3", "m4a", "flac", "ogg")[i % 5]
    audio(i, "Export %02d — session take.$ext".format(i), 900_000L * i, 7_300L * i)
}

private val longNames = listOf(
    audio(1, "An extremely long exported mixdown name that keeps going well past the width of the row (final v3).wav", 52_000_000, 301_000),
    audio(2, "Ünïcödé — ナイトドライブ — العربية — long name test.mp3", 5_100_000, 211_000),
    audio(3, "no_extension_export", 800_000, 9_000),
    audio(4, "Short.wav", 120_000, 1_000),
)

@Composable
private fun LibraryFrame(content: @Composable () -> Unit) = ResoundTheme {
    // Same structure as MainActivity: the Scaffold supplies the system-bar and navigation padding.
    Scaffold(bottomBar = { ResoundBottomBar(ResoundDestination.Library, onSelect = { EditorPreviewEvents.record("nav:${it.name}") }) }) { padding ->
        Box(Modifier.padding(padding)) { content() }
    }
}

@Composable
private fun Library(exports: List<LibraryAudio>, permission: Boolean = true, loading: Boolean = false, error: String? = null) =
    LibraryWorkspace(
        exports = exports,
        permissionGranted = permission,
        loading = loading,
        error = error,
        folder = FOLDER,
        onRefresh = { EditorPreviewEvents.record("refresh") },
        onAllowAccess = { EditorPreviewEvents.record("allow-access") },
        onOpen = { EditorPreviewEvents.record("open:${it.displayName}") },
        onShare = { EditorPreviewEvents.record("share:${it.displayName}") },
    )

@Preview(widthDp = 412, heightDp = 850) @Composable fun LibraryPreviewPopulated() = LibraryFrame { Library(few) }
@Preview(widthDp = 412, heightDp = 850) @Composable fun LibraryPreviewMany() = LibraryFrame { Library(many) }
@Preview(widthDp = 412, heightDp = 850) @Composable fun LibraryPreviewLongNames() = LibraryFrame { Library(longNames) }
@Preview(widthDp = 412, heightDp = 850) @Composable fun LibraryPreviewEmpty() = LibraryFrame { Library(emptyList()) }
@Preview(widthDp = 412, heightDp = 850) @Composable fun LibraryPreviewPermission() = LibraryFrame { Library(emptyList(), permission = false) }
@Preview(widthDp = 412, heightDp = 850) @Composable fun LibraryPreviewLoading() = LibraryFrame { Library(emptyList(), loading = true) }
@Preview(widthDp = 412, heightDp = 850) @Composable
fun LibraryPreviewError() = LibraryFrame { Library(emptyList(), error = "Could not read Music/Resound: storage unavailable") }

/**
 * Inset probe reproducing the PRE-Phase-4 Library modifier chain (safeDrawingPadding inside the
 * Scaffold content) so injected window insets show the duplicated top/bottom padding. Debug only.
 */
@Preview(widthDp = 412, heightDp = 850) @Composable
fun LibraryPreviewHeadInsetChain() = LibraryFrame {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("Library", style = MaterialTheme.typography.headlineSmall)
        Text("Pre-Phase-4 modifier chain (probe)", style = MaterialTheme.typography.bodySmall)
    }
}
