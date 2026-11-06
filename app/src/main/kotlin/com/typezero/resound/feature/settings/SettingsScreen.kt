/*
 * file:    SettingsScreen.kt
 * author:  Typezer∅
 * version: 0.8.0-dev.6
 * desc:    Premium settings and application-information workspace.
 */
package com.typezero.resound.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.typezero.resound.feature.about.AboutDialog
import com.typezero.resound.feature.about.UpdateDialog
import com.typezero.resound.ui.components.ResoundCard
import com.typezero.resound.ui.theme.Line
import com.typezero.resound.ui.theme.PanelHi
import com.typezero.resound.ui.theme.Signal
import com.typezero.resound.ui.theme.TextLo

private const val CHANGELOG_URL =
    "https://github.com/MikereDD/Resound/blob/main/CHANGELOG.md"

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var showAbout by remember { mutableStateOf(false) }
    var showUpdater by remember { mutableStateOf(false) }

    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsHeader(version = version)

        SettingsSectionTitle("APPLICATION")
        ResoundCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                SettingsActionRow(
                    icon = Icons.Outlined.SystemUpdate,
                    title = "Check for updates",
                    subtitle = "Secure manifest and APK verification",
                    onClick = { showUpdater = true },
                )
                SettingsDivider()
                SettingsActionRow(
                    icon = Icons.Outlined.Info,
                    title = "About Resound",
                    subtitle = "Credits, project details, and licensing",
                    onClick = { showAbout = true },
                )
                SettingsDivider()
                SettingsActionRow(
                    icon = Icons.Outlined.OpenInNew,
                    title = "View changelog",
                    subtitle = "Release notes and development history",
                    onClick = { open(CHANGELOG_URL) },
                )
            }
        }

        SettingsSectionTitle("WORKSPACE")
        ResoundCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                SettingsInfoRow(
                    icon = Icons.Outlined.Folder,
                    title = "Export destination",
                    value = com.typezero.resound.core.io.Outputs.musicDirectoryDisplay,
                )
                SettingsDivider()
                SettingsInfoRow(
                    icon = Icons.Outlined.GraphicEq,
                    title = "Processing engine",
                    value = "FFmpeg · on device",
                )
                SettingsDivider()
                SettingsInfoRow(
                    icon = Icons.Outlined.Lock,
                    title = "Privacy",
                    value = "Local-first · no account · no ads",
                )
            }
        }

        Text(
            "Typezer∅ Studio · Resound",
            style = MaterialTheme.typography.labelMedium,
            color = TextLo,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
        )
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showUpdater) UpdateDialog(onDismiss = { showUpdater = false })
}

@Composable
private fun SettingsHeader(version: String) {
    ResoundCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = Signal.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Signal.copy(alpha = 0.45f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = null,
                        tint = Signal,
                        modifier = Modifier.size(25.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Workspace, updates, and application details",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextLo,
                )
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = PanelHi,
                border = BorderStroke(1.dp, Line),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        "DEVELOPMENT",
                        style = MaterialTheme.typography.labelSmall,
                        color = Signal,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        version,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Signal,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
    )
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsIcon(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextLo)
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = TextLo,
        )
    }
}

@Composable
private fun SettingsInfoRow(
    icon: ImageVector,
    title: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsIcon(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.bodySmall, color = TextLo)
        }
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector) {
    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Signal) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun SettingsDivider() {
    Divider(color = Line.copy(alpha = 0.65f))
}
