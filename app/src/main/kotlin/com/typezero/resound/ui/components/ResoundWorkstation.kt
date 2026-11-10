/*
 * file:    ResoundWorkstation.kt
 * desc:    Shared workstation controls: a compact screen header, near-black panels, section labels,
 *          hairline dividers, 48dp icon actions, outlined actions (primary / secondary) and choice chips.
 *          Steel Blue (Accent) marks interactive controls and selection; informational icons are
 *          neutral grey (IconInfo). Visual only; no behaviour lives here.
 */
package com.typezero.resound.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typezero.resound.ui.theme.Accent
import com.typezero.resound.ui.theme.AccentLine
import com.typezero.resound.ui.theme.ControlIcon
import com.typezero.resound.ui.theme.ControlLabel
import com.typezero.resound.ui.theme.Ink
import com.typezero.resound.ui.theme.InkRaised
import com.typezero.resound.ui.theme.Line
import com.typezero.resound.ui.theme.LineSoft
import com.typezero.resound.ui.theme.Panel
import com.typezero.resound.ui.theme.PanelPressed
import com.typezero.resound.ui.theme.TextHi
import com.typezero.resound.ui.theme.TextLo
import com.typezero.resound.ui.theme.TextMid

/** One-line screen header: Steel Blue section icon, title (a heading), optional subtitle and trailing actions. */
@Composable
fun ResoundScreenHeader(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(ControlIcon.Header))
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

/** Near-black panel with a thin border; replaces large rounded cards on list screens. */
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

/** Small uppercase group label in readable grey, exposed to accessibility services as a heading. */
@Composable
fun ResoundSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp),
        fontWeight = FontWeight.SemiBold,
        color = TextMid,
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

/** 48dp icon action in Steel Blue; disabled icons drop to dim grey. */
@Composable
fun ResoundIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = ControlIcon.Toolbar,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) Accent else TextLo.copy(alpha = 0.45f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Primary: Steel Blue outline and 30dp icon (Export Mix reference). Secondary: neutral outline, smaller icon. */
enum class ActionProminence { Primary, Secondary }

/** Resolved colours and widths of an outlined action for one interaction state. Pure, so it is unit-tested. */
@Immutable
data class ActionStyle(
    val container: Color,
    val border: Color,
    val borderWidth: Dp,
    val icon: Color,
    val label: Color,
    val iconSize: Dp,
)

fun actionStyle(prominence: ActionProminence, enabled: Boolean, pressed: Boolean, focused: Boolean): ActionStyle {
    val primary = prominence == ActionProminence.Primary
    val iconSize = if (primary) ControlIcon.Primary else ControlIcon.Toolbar - 4.dp
    if (!enabled) return ActionStyle(InkRaised, Line, 1.dp, TextLo.copy(alpha = 0.45f), TextLo, iconSize)
    return ActionStyle(
        container = if (pressed) PanelPressed else if (primary) Ink else InkRaised,
        // Focus and press thicken the outline and switch it to full Steel Blue: a shape cue, not colour alone.
        border = if (pressed || focused) Accent else if (primary) AccentLine else Line,
        borderWidth = if (pressed || focused) 2.dp else 1.dp,
        icon = Accent,
        label = ControlLabel,
        iconSize = iconSize,
    )
}

/** Outlined action (at least 48dp tall) with explicit pressed, focused, enabled and disabled appearances. */
@Composable
fun ResoundAction(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominence: ActionProminence = ActionProminence.Primary,
    iconSize: Dp? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    labelSize: TextUnit = 14.sp,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val style = actionStyle(prominence, enabled, pressed, focused)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = contentPadding,
        border = BorderStroke(style.borderWidth, style.border),
        colors = ButtonDefaults.buttonColors(
            containerColor = style.container,
            contentColor = style.label,
            disabledContainerColor = style.container,
            disabledContentColor = style.label,
        ),
        elevation = null,
        interactionSource = source,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = style.icon, modifier = Modifier.size(iconSize ?: style.iconSize))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = labelSize, lineHeight = 18.sp,
                fontWeight = if (prominence == ActionProminence.Primary) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 0.sp))
    }
}

/** Colours for stock text-only OutlinedButtons: light-grey label instead of Material's primary (Steel Blue) text. */
@Composable
fun resoundOutlinedTextColors() = ButtonDefaults.outlinedButtonColors(
    containerColor = Color.Transparent,
    contentColor = ControlLabel,
    disabledContentColor = TextLo.copy(alpha = 0.45f),
)

/** Single-choice option: selection shows as a 2dp Steel Blue outline, accent text and semibold weight. */
@Composable
fun ResoundChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics { this.selected = selected },
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Ink else Panel,
            contentColor = if (selected) Accent else TextMid,
        ),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Accent else Line),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
