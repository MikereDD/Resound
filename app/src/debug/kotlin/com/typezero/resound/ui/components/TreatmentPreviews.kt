package com.typezero.resound.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.typezero.resound.feature.edit.EditorPreviewEvents
import com.typezero.resound.feature.library.LibraryAudio
import com.typezero.resound.feature.library.LibraryWorkspace
import com.typezero.resound.feature.settings.SettingsWorkspace
import com.typezero.resound.feature.settings.releaseChannel
import com.typezero.resound.ui.theme.ResoundTheme
import com.typezero.resound.ui.theme.TextLo
import com.typezero.resound.ui.theme.TextMid

/*
 * Debug-only comparison of the approved cyan control treatment with the PROVISIONAL Steel Blue
 * candidate on real Library and Settings controls. Nothing here is used by the app; the global
 * accent is unchanged until the owner approves.
 */
private val SteelBlue = Color(0xFF769DB8)
private val SteelLabel = Color(0xFFD3DAE0)

internal val SteelBlueTreatment = ControlTreatment(
    accent = SteelBlue,
    passiveIcon = TextLo,              // inactive / informational icons: neutral grey
    sectionLabel = TextMid,            // labels: readable light grey, not accent-coloured
    leadingIcon = 30.dp,               // primary action icons ~30dp
    actionIcon = 28.dp,                // toolbar icons ~28dp
    buttonContainer = Color.Transparent, // no tinted fill, gradient or glow
    buttonContent = SteelLabel,
    buttonBorder = SteelBlue.copy(alpha = 0.7f),
)

private const val MODIFIED = 1_794_000_000L
private val fixtures = listOf(
    LibraryAudio(Uri.parse("content://resound.ui.fixture/t/1"), "Midnight Drive (master).wav", "audio/wav", 46_200_000, 262_000, MODIFIED),
    LibraryAudio(Uri.parse("content://resound.ui.fixture/t/2"), "Voice memo — trimmed.m4a", "audio/mp4", 1_310_000, 81_000, MODIFIED - 86_400),
    LibraryAudio(Uri.parse("content://resound.ui.fixture/t/3"), "Drum loop 120bpm.mp3", "audio/mpeg", 3_900_000, 32_000, MODIFIED - 172_800),
)

@Composable
private fun Treated(steel: Boolean, destination: ResoundDestination, content: @Composable () -> Unit) = ResoundTheme {
    CompositionLocalProvider(LocalControlTreatment provides if (steel) SteelBlueTreatment else ControlTreatment.Current) {
        Scaffold(bottomBar = { ResoundBottomBar(destination, onSelect = {}) }) { padding ->
            Box(Modifier.padding(padding)) { content() }
        }
    }
}

@Composable
private fun LibraryCompare(steel: Boolean, permission: Boolean) = Treated(steel, ResoundDestination.Library) {
    LibraryWorkspace(
        exports = if (permission) fixtures else emptyList(), permissionGranted = permission, loading = false, error = null,
        folder = "Music/Resound", onRefresh = { EditorPreviewEvents.record("refresh") },
        onAllowAccess = { EditorPreviewEvents.record("allow-access") },
        onOpen = { EditorPreviewEvents.record("open") }, onShare = { EditorPreviewEvents.record("share") },
    )
}

@Composable
private fun SettingsCompare(steel: Boolean) = Treated(steel, ResoundDestination.Settings) {
    SettingsWorkspace("0.8.0-dev.12", releaseChannel("0.8.0-dev.12"), {}, {}, {})
}

/** Button and icon specimens: the shared outline action (also used for an Export Mix candidate) and icon actions. */
@Composable
private fun ControlsCompare(steel: Boolean) = Treated(steel, ResoundDestination.Library) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (steel) "Proposed: Steel Blue #769DB8" else "Current: approved cyan", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ResoundOutlineAction("Export Mix", Icons.Outlined.FileUpload, onClick = {})
            ResoundOutlineAction("Export Mix", Icons.Outlined.FileUpload, onClick = {}, enabled = false)
        }
        ResoundOutlineAction("Allow access", Icons.Outlined.LockOpen, onClick = {})
        Row(verticalAlignment = Alignment.CenterVertically) {
            ResoundIconAction(Icons.Outlined.Refresh, "Refresh", onClick = {})
            ResoundIconAction(Icons.Outlined.Share, "Share", onClick = {})
            ResoundIconAction(Icons.Outlined.Refresh, "Refresh (disabled)", onClick = {}, enabled = false)
        }
        Text("Export Mix here is the shared outline action under each treatment; the live Multitrack button is unchanged.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
}

@Preview(widthDp = 412, heightDp = 850) @Composable fun TreatmentLibraryCurrent() = LibraryCompare(steel = false, permission = true)
@Preview(widthDp = 412, heightDp = 850) @Composable fun TreatmentLibrarySteel() = LibraryCompare(steel = true, permission = true)
@Preview(widthDp = 412, heightDp = 850) @Composable fun TreatmentPermissionCurrent() = LibraryCompare(steel = false, permission = false)
@Preview(widthDp = 412, heightDp = 850) @Composable fun TreatmentPermissionSteel() = LibraryCompare(steel = true, permission = false)
@Preview(widthDp = 412, heightDp = 850) @Composable fun TreatmentSettingsCurrent() = SettingsCompare(steel = false)
@Preview(widthDp = 412, heightDp = 850) @Composable fun TreatmentSettingsSteel() = SettingsCompare(steel = true)
@Preview(widthDp = 412, heightDp = 850) @Composable fun TreatmentControlsCurrent() = ControlsCompare(steel = false)
@Preview(widthDp = 412, heightDp = 850) @Composable fun TreatmentControlsSteel() = ControlsCompare(steel = true)
