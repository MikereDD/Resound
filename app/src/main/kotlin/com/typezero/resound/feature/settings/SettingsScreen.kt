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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typezero.resound.core.update.TypezeroVersion
import com.typezero.resound.feature.about.AboutDialog
import com.typezero.resound.feature.about.UpdateDialog
import com.typezero.resound.ui.components.ResoundHairline
import com.typezero.resound.ui.components.ResoundPanel
import com.typezero.resound.ui.components.ResoundScreenHeader
import com.typezero.resound.ui.components.ResoundSectionLabel
import com.typezero.resound.ui.theme.Accent
import com.typezero.resound.ui.theme.ControlIcon
import com.typezero.resound.ui.theme.IconInfo
import com.typezero.resound.ui.theme.Line
import com.typezero.resound.ui.theme.PanelHi
import com.typezero.resound.ui.theme.TextHi
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

    SettingsWorkspace(
        version = version,
        channel = releaseChannel(version),
        onCheckUpdates = { showUpdater = true },
        onAbout = { showAbout = true },
        onChangelog = { open(CHANGELOG_URL) },
        modifier = modifier,
    )

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showUpdater) UpdateDialog(onDismiss = { showUpdater = false })
}

/**
 * Release channel shown next to the version, derived from the installed versionName through the
 * same Typezer∅ grammar the updater uses: `-dev.N` builds are DEVELOPMENT, plain versions STABLE.
 * Unparseable or missing versions show no channel rather than a guess.
 */
internal enum class ReleaseChannel(val label: String) { DEVELOPMENT("DEVELOPMENT"), STABLE("STABLE") }

internal fun releaseChannel(versionName: String?): ReleaseChannel? {
    val parsed = versionName?.let { runCatching { TypezeroVersion.parse(it) }.getOrNull() } ?: return null
    return if (parsed.isDevelopment) ReleaseChannel.DEVELOPMENT else ReleaseChannel.STABLE
}

/** Badge text beside the version: only development builds are marked; stable and unknown show the version alone. */
internal fun channelBadge(channel: ReleaseChannel?): String? = if (channel == ReleaseChannel.DEVELOPMENT) channel.label else null

/** Display-only Settings body; dialogs, version lookup and links stay in [SettingsScreen]. */
@Composable
internal fun SettingsWorkspace(
    version: String,
    channel: ReleaseChannel?,
    onCheckUpdates: () -> Unit,
    onAbout: () -> Unit,
    onChangelog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        // The version chip shares the header line only when the title keeps readable room beside it
        // (measured); on narrow screens or with large text it moves under the title instead.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val inline = versionChipFitsInline(maxWidth, version, channel)
            Column {
                ResoundScreenHeader(
                    icon = Icons.Outlined.Settings,
                    title = SETTINGS_TITLE,
                    subtitle = SETTINGS_SUBTITLE,
                ) {
                    if (inline) {
                        VersionChip(version, channel)
                        Spacer(Modifier.width(12.dp))
                    }
                }
                if (!inline) {
                    Row(Modifier.padding(start = 16.dp + ControlIcon.Header + 12.dp, bottom = 10.dp)) {
                        VersionChip(version, channel)
                    }
                }
            }
        }
        ResoundHairline()

        ResoundSectionLabel("APPLICATION")
        ResoundPanel(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            SettingsRow(Icons.Outlined.SystemUpdate, "Check for updates", "Secure manifest and APK verification", onCheckUpdates)
            ResoundHairline(startIndent = ROW_TEXT_INDENT)
            SettingsRow(Icons.Outlined.Info, "About Resound", "Credits, project details, and licensing", onAbout)
            ResoundHairline(startIndent = ROW_TEXT_INDENT)
            SettingsRow(Icons.AutoMirrored.Outlined.OpenInNew, "View changelog", "Release notes and development history", onChangelog)
        }

        ResoundSectionLabel("WORKSPACE")
        ResoundPanel(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            SettingsRow(Icons.Outlined.Folder, "Export destination", com.typezero.resound.core.io.Outputs.musicDirectoryDisplay)
            ResoundHairline(startIndent = ROW_TEXT_INDENT)
            SettingsRow(Icons.Outlined.GraphicEq, "Processing engine", "FFmpeg · on device")
            ResoundHairline(startIndent = ROW_TEXT_INDENT)
            SettingsRow(Icons.Outlined.Lock, "Privacy", "Local-first · no account · no ads")
        }

        Text(
            "Typezer∅ Studio · Resound",
            style = MaterialTheme.typography.labelMedium,
            color = TextLo,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        )
    }
}

private val ROW_TEXT_INDENT = 56.dp
private const val SETTINGS_TITLE = "Settings"
private const val SETTINGS_SUBTITLE = "Updates, workspace and application details"
private val CHIP_CHANNEL_STYLE_SIZE = 10.sp
private val CHIP_VERSION_STYLE_SIZE = 12.sp

@Composable
private fun versionChipFitsInline(maxWidth: Dp, version: String, channel: ReleaseChannel?): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = MaterialTheme.typography.labelMedium
    return remember(maxWidth, version, channel, density, measurer) {
        fun width(text: String, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight, spacing: Float) =
            measurer.measure(text, base.copy(fontSize = size, fontWeight = weight, letterSpacing = spacing.sp)).size.width
        val chipPx = maxOf(width(version, CHIP_VERSION_STYLE_SIZE, FontWeight.Medium, 0f),
            channelBadge(channel)?.let { width(it, CHIP_CHANNEL_STYLE_SIZE, FontWeight.Bold, 0.6f) } ?: 0)
        val titlePx = measurer.measure(SETTINGS_TITLE, base.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold)).size.width
        // The two-line subtitle must also fit beside the chip (about 55% of its one-line width per line).
        val subtitlePx = measurer.measure(SETTINGS_SUBTITLE, base.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal)).size.width
        with(density) {
            val fixed = (16.dp + ControlIcon.Header + 12.dp + 4.dp + 12.dp + 22.dp).toPx()  // insets, icon, gaps, chip padding
            maxWidth.toPx() - fixed - chipPx >= maxOf(titlePx.toFloat(), subtitlePx * 0.55f, 120.dp.toPx())
        }
    }
}

@Composable
private fun VersionChip(version: String, channel: ReleaseChannel?) {
    val badge = channelBadge(channel)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = PanelHi,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalAlignment = Alignment.End) {
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = CHIP_CHANNEL_STYLE_SIZE, lineHeight = 14.sp, letterSpacing = 0.6.sp),
                    fontWeight = FontWeight.Bold,
                    color = Accent,
                )
            }
            // A version is always left-to-right; in RTL layouts bidi would otherwise show "dev.12-0.8.0".
            Text(version, style = MaterialTheme.typography.labelMedium.copy(fontSize = CHIP_VERSION_STYLE_SIZE, lineHeight = 16.sp,
                textDirection = TextDirection.Ltr), color = TextHi)
        }
    }
}

/** Slim row: action rows (with [onClick]) are buttons with a chevron; information rows are plain text. */
@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    val interaction = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(interaction)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            // Steel Blue marks actions; information rows use neutral grey.
            tint = if (onClick != null) Accent else IconInfo,
            modifier = Modifier.size(ControlIcon.Primary),
        )
        Spacer(Modifier.width((ROW_TEXT_INDENT - 14.dp - ControlIcon.Primary).coerceAtLeast(4.dp)))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
                fontWeight = FontWeight.Medium,
                color = TextHi,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp), color = TextLo)
        }
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = TextLo,
                modifier = Modifier.size(22.dp))
        }
    }
}
