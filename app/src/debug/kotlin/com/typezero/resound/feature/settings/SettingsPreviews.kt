package com.typezero.resound.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.typezero.resound.feature.edit.EditorPreviewEvents
import com.typezero.resound.ui.components.ResoundBottomBar
import com.typezero.resound.ui.components.ResoundDestination
import com.typezero.resound.ui.theme.ResoundTheme

/** Debug-only Settings fixtures: the version string goes through the real channel resolver. */
@Composable
private fun SettingsFrame(version: String) = ResoundTheme {
    Scaffold(bottomBar = { ResoundBottomBar(ResoundDestination.Settings, onSelect = { EditorPreviewEvents.record("nav:${it.name}") }) }) { padding ->
        Box(Modifier.padding(padding)) {
            SettingsWorkspace(
                version = version,
                channel = releaseChannel(version),
                onCheckUpdates = { EditorPreviewEvents.record("check-updates") },
                onAbout = { EditorPreviewEvents.record("about") },
                onChangelog = { EditorPreviewEvents.record("changelog") },
            )
        }
    }
}

@Preview(widthDp = 412, heightDp = 850) @Composable fun SettingsPreviewDevelopment() = SettingsFrame("0.8.0-dev.12")
@Preview(widthDp = 412, heightDp = 850) @Composable fun SettingsPreviewStable() = SettingsFrame("1.0")
@Preview(widthDp = 412, heightDp = 850) @Composable fun SettingsPreviewUnknownVersion() = SettingsFrame("—")
