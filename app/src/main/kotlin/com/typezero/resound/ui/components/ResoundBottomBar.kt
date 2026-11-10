package com.typezero.resound.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typezero.resound.ui.theme.Ink
import com.typezero.resound.ui.theme.LineSoft
import com.typezero.resound.ui.theme.Accent
import com.typezero.resound.ui.theme.AccentLine
import com.typezero.resound.ui.theme.ControlIcon
import com.typezero.resound.ui.theme.TextLo

enum class ResoundDestination(val label: String) {
    Editor("Editor"),
    Multitrack("Multitrack"),
    Library("Library"),
    Settings("Settings"),
}

@Composable
fun ResoundBottomBar(
    selected: ResoundDestination,
    onSelect: (ResoundDestination) -> Unit,
) {
    Surface(color = Ink, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column {
            HorizontalDivider(color = LineSoft)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                    .height(IntrinsicSize.Min)
                    .selectableGroup(),
            ) {
                ResoundDestination.entries.forEach { destination ->
                    val icon = when (destination) {
                        ResoundDestination.Editor -> Icons.Outlined.AudioFile
                        ResoundDestination.Multitrack -> Icons.Outlined.Tune
                        ResoundDestination.Library -> Icons.Outlined.LibraryMusic
                        ResoundDestination.Settings -> Icons.Outlined.Settings
                    }
                    val active = selected == destination
                    val tint = if (active) Accent else TextLo
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = active,
                                role = Role.Tab,
                                onClick = { onSelect(destination) },
                            )
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        // Selected tab: thin Steel Blue outline around the icon, accent tint and a semibold label
                        // (shape and weight cues, not colour alone). No fill or glow.
                        Box(
                            modifier = Modifier
                                .size(width = 58.dp, height = 32.dp)
                                .then(if (active) Modifier.border(1.dp, AccentLine, RoundedCornerShape(16.dp)) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(ControlIcon.Navigation))
                        }
                        NavLabel(destination, tint, active)
                    }
                }
            }
        }
    }
}

/**
 * Readable label at every font scale: the whole word on one line when it fits, otherwise the
 * word split at a natural hyphen over two lines (never broken mid-word, never shrunk).
 */
@Composable
private fun NavLabel(destination: ResoundDestination, tint: Color, active: Boolean) {
    val style = MaterialTheme.typography.labelMedium.copy(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
    )
    val measurer = rememberTextMeasurer()
    // The bar spans the window minus the navigation-bar insets it pads by, and each tab has 4dp
    // side padding. Not a subcomposing layout: the tab row asks for intrinsic heights, which
    // BoxWithConstraints cannot answer (crash). The 2dp margin absorbs per-tab pixel rounding,
    // so a label that only just fits is split cleanly instead of wrapping mid-word.
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = NavigationBarDefaults.windowInsets
    val availablePx = with(density) {
        val barPx = LocalConfiguration.current.screenWidthDp.dp.toPx() -
            insets.getLeft(density, direction) - insets.getRight(density, direction)
        (barPx / ResoundDestination.entries.size - 10.dp.toPx()).toInt()
    }
    val fits = remember(destination, style, availablePx, measurer) {
        measurer.measure(destination.label, style, maxLines = 1, softWrap = false).size.width <= availablePx
    }
    Text(
        text = if (fits) destination.label else navLabelLines(destination.label),
        color = tint,
        // The hyphenated split is English-specific; keep it LTR so an RTL layout does not move the
        // hyphen to the start of the line ("-Multi").
        style = if (fits) style else style.copy(textDirection = TextDirection.Ltr),
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = destination.label },
    )
}

/** Two-line form of a destination label, split at its natural syllable boundary. */
internal fun navLabelLines(label: String): String = when (label) {
    "Multitrack" -> "Multi-\ntrack"
    "Settings" -> "Set-\ntings"
    "Library" -> "Lib-\nrary"
    "Editor" -> "Ed-\nitor"
    else -> label
}
