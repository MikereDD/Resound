/*
 * file:    Theme.kt
 * author:  Typezer∅
 * version: 0.8.0-dev.1
 * desc:    Resound's visual system — a darkroom for sound. OLED-black
 *          surfaces, cyan interaction states, restrained amber live
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
val Ink = Color(0xFF000000)
val InkRaised = Color(0xFF07090B)
val Panel = Color(0xFF0B0E11)
val PanelHi = Color(0xFF13181C)
val PanelPressed = Color(0xFF1C2429)
val Line = Color(0xFF29343B)
val LineSoft = Color(0xFF192126)
// Data colours: waveform body and the first track identity. Not used for controls.
val Signal = Color(0xFF00D4DA)
val SignalDeep = Color(0xFF004D52)

// Interactive accent (owner-approved Steel Blue): controls, selection and meaningful active states.
val Accent = Color(0xFF769DB8)
val AccentLine = Color(0xB3769DB8)       // thin, understated outline on primary actions (70%)
val AccentDeep = Color(0xFF22303A)       // Material primaryContainer; no glow or tinted fills elsewhere
val ControlLabel = Color(0xFFD3DAE0)     // readable light-grey label on controls
val IconInfo = Color(0xFF8A9AA6)         // neutral grey for informational, non-interactive icons
val Amber = Color(0xFFF4A62A)
val TextHi = Color(0xFFF0F5F7)
val TextMid = Color(0xFFB5C0C8)
val TextLo = Color(0xFF8A9AA6)

private val ResoundColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    primaryContainer = AccentDeep,
    onPrimaryContainer = TextHi,
    secondary = Amber,
    onSecondary = Ink,
    secondaryContainer = PanelHi,
    onSecondaryContainer = TextHi,
    tertiary = Accent,
    background = Ink,
    onBackground = TextHi,
    surface = Panel,
    onSurface = TextHi,
    surfaceTint = Color.Transparent,
    surfaceVariant = PanelHi,
    onSurfaceVariant = TextMid,
    surfaceDim = Ink,
    surfaceBright = PanelPressed,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = InkRaised,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHi,
    surfaceContainerHighest = PanelPressed,
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

/** Icon size targets; layouts may step down where a target would clip (always inside 48dp touch targets). */
object ControlIcon {
    val Primary = 30.dp
    val Toolbar = 28.dp
    val Navigation = 26.dp
    val Header = 26.dp
}

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
