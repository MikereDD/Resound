/*
 * file:    Theme.kt
 * author:  Typezer∅
 * version: 0.8.0-dev.1
 * desc:    Resound's premium visual system — a darkroom for sound. Deep ink
 *          surfaces, signal-teal interaction states, restrained amber live
 *          states, strong typography, and consistent studio-grade geometry.
 */
package com.typezero.resound.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

// Core palette
val Ink = Color(0xFF070B11)
val InkRaised = Color(0xFF0B1119)
val Panel = Color(0xFF101923)
val PanelHi = Color(0xFF182532)
val PanelPressed = Color(0xFF20313F)
val Line = Color(0xFF263746)
val LineSoft = Color(0xFF1B2935)
val Signal = Color(0xFF2ED6C6)
val SignalDeep = Color(0xFF087F78)
val SignalGlow = Color(0x332ED6C6)
val Amber = Color(0xFFF4A62A)
val TextHi = Color(0xFFF0F5F7)
val TextMid = Color(0xFFB5C2CC)
val TextLo = Color(0xFF7F93A3)

private val ResoundColors = darkColorScheme(
    primary = Signal,
    onPrimary = Ink,
    primaryContainer = SignalDeep,
    onPrimaryContainer = TextHi,
    secondary = Amber,
    onSecondary = Ink,
    secondaryContainer = Color(0xFF573A0E),
    onSecondaryContainer = Color(0xFFFFD89A),
    tertiary = Signal,
    background = Ink,
    onBackground = TextHi,
    surface = Panel,
    onSurface = TextHi,
    surfaceVariant = PanelHi,
    onSurfaceVariant = TextMid,
    surfaceContainer = InkRaised,
    surfaceContainerHigh = PanelHi,
    outline = Line,
    outlineVariant = LineSoft,
    error = Color(0xFFFF7373),
)

val Mono = FontFamily.Monospace

private val ResoundType = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.55).sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
        ),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.25.sp,
        ),
        bodyLarge = base.bodyLarge.copy(color = TextHi),
        bodyMedium = base.bodyMedium.copy(color = TextMid),
        bodySmall = base.bodySmall.copy(color = TextLo),
    )
}

val TimecodeStyle = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    letterSpacing = 0.5.sp,
)

private val ResoundShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object ResoundDimens {
    val ScreenHorizontal = 20.dp
    val ScreenVertical = 16.dp
    val CardPadding = 16.dp
    val SectionGap = 16.dp
    val ControlHeight = 52.dp
}

@Composable
fun ResoundTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ResoundColors,
        typography = ResoundType,
        shapes = ResoundShapes,
        content = content,
    )
}
