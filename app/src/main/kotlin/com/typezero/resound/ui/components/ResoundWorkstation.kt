/*
 * file:    ResoundWorkstation.kt
 * desc:    Shared building blocks for the list-style workstation screens (Library, Settings):
 *          a compact screen header, near-black panels, section labels, hairline dividers and
 *          48dp icon / outline actions. Visual only; no behaviour lives here.
 */
package com.typezero.resound.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typezero.resound.ui.theme.InkRaised
import com.typezero.resound.ui.theme.Line
import com.typezero.resound.ui.theme.LineSoft
import com.typezero.resound.ui.theme.Panel
import com.typezero.resound.ui.theme.Signal
import com.typezero.resound.ui.theme.SignalGlow
import com.typezero.resound.ui.theme.TextHi
import com.typezero.resound.ui.theme.TextLo


/**
 * Accent colour and icon sizing for interactive controls. [Current] is the approved cyan treatment and
 * the only one used by the app; alternatives are supplied through [LocalControlTreatment] by debug
 * previews so the owner can compare them on real controls before any global change.
 */
@Immutable
data class ControlTreatment(
    val accent: Color,
    val passiveIcon: Color,
    val sectionLabel: Color,
    val leadingIcon: Dp,
    val actionIcon: Dp,
    val buttonContainer: Color,
    val buttonContent: Color,
    val buttonBorder: Color,
) {
    companion object {
        val Current = ControlTreatment(
            accent = Signal,
            passiveIcon = Signal,
            sectionLabel = Signal,
            leadingIcon = 22.dp,
            actionIcon = 24.dp,
            buttonContainer = SignalGlow,
            buttonContent = Signal,
            buttonBorder = Signal,
        )
    }
}

val LocalControlTreatment = staticCompositionLocalOf { ControlTreatment.Current }

/** One-line screen header: accent icon, title (a heading), optional subtitle and trailing actions. */
@Composable
fun ResoundScreenHeader(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val treatment = LocalControlTreatment.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = treatment.accent, modifier = Modifier.size(treatment.leadingIcon))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp),
                color = TextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = TextLo,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/** Near-black panel with a thin border; replaces the large rounded cards on list screens. */
@Composable
fun ResoundPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Panel,
        border = BorderStroke(1.dp, Line),
    ) {
        Column(content = content)
    }
}

/** Small uppercase group label, exposed to accessibility services as a heading. */
@Composable
fun ResoundSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp),
        fontWeight = FontWeight.SemiBold,
        color = LocalControlTreatment.current.sectionLabel,
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
            .semantics { heading() },
    )
}

/** 1dp separator between rows (not Dp.Hairline, which may draw a single physical pixel); [startIndent] aligns it with the row text. */
@Composable
fun ResoundHairline(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    HorizontalDivider(modifier = modifier.padding(start = startIndent), thickness = 1.dp, color = LineSoft)
}

/** 48dp icon action tinted with the control accent. */
@Composable
fun ResoundIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val treatment = LocalControlTreatment.current
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) treatment.accent else TextLo,
            modifier = Modifier.size(treatment.actionIcon),
        )
    }
}

/** Compact bordered action button (at least 48dp tall) for inline panels. */
@Composable
fun ResoundOutlineAction(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val treatment = LocalControlTreatment.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        border = BorderStroke(1.dp, if (enabled) treatment.buttonBorder else Line),
        colors = ButtonDefaults.buttonColors(
            containerColor = treatment.buttonContainer,
            contentColor = treatment.buttonContent,
            disabledContainerColor = InkRaised,
            disabledContentColor = TextLo,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (enabled) treatment.accent else TextLo,
                modifier = Modifier.size(treatment.actionIcon - 4.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp))
    }
}
