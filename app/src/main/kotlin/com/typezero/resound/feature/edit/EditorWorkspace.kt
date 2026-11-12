package com.typezero.resound.feature.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.AudioFile as AudioFileIcon
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.typezero.resound.core.audio.AudioFile
import com.typezero.resound.core.audio.Waveform
import com.typezero.resound.core.audio.WaveformProvenance
import com.typezero.resound.feature.record.RecordingRecoveryAdmission
import com.typezero.resound.ui.theme.Amber
import com.typezero.resound.ui.theme.InkRaised
import com.typezero.resound.ui.theme.Line
import com.typezero.resound.ui.theme.LineSoft
import com.typezero.resound.ui.theme.Panel
import com.typezero.resound.ui.components.ActionProminence
import com.typezero.resound.ui.components.ResoundAction
import com.typezero.resound.ui.components.resoundOutlinedTextColors
import com.typezero.resound.ui.theme.Accent
import com.typezero.resound.ui.theme.Ink
import com.typezero.resound.ui.theme.AccentLine
import com.typezero.resound.ui.theme.ControlIcon
import com.typezero.resound.ui.theme.IconInfo
import com.typezero.resound.ui.theme.TextHi
import com.typezero.resound.ui.theme.TextLo
import com.typezero.resound.ui.theme.TextMid
import com.typezero.resound.ui.theme.TimecodeStyle
import kotlin.math.ceil

/** Display-only inputs. Readiness and admission remain owned by EditScreen. */
internal data class EditorWorkspaceUiState(
    val file: AudioFile? = null,
    val waveform: Waveform? = null,
    val selStart: Long = 0L,
    val selEnd: Long = 0L,
    val playhead: Long = 0L,
    val status: String = "Open a file or record to begin.",
    val busy: Boolean = false,
    val sourceBusy: Boolean = false,
    val recording: Boolean = false,
    val recordElapsedMs: Long = 0L,
    val playing: Boolean = false,
    val closeEnabled: Boolean = false,
    val closeError: String? = null,
    val transportEnabled: Boolean = false,
    val toolsEnabled: Boolean = false,
    val outputDirectory: String = "Music/Resound",
    val namingAvailable: Boolean = true,
)

/** Existing controller callbacks are forwarded without interpreting or replacing them. */
internal data class EditorWorkspaceCallbacks(
    val onOpen: () -> Unit = {},
    val onRecord: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onSelectionChange: (Long, Long) -> Unit = { _, _ -> },
    val onSeekBack: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onSeekForward: () -> Unit = {},
    val onTool: (EditOp) -> Unit = {},
    val onRingtone: () -> Unit = {},
)

private enum class EditorOptions { More, Recording, Export }

internal enum class EditorOptionEntry { RecordingName, ExportName, CloseAudio }

/** Options-dialog entries: naming needs Android 10+, export naming a loaded workspace, Close a loaded file. */
internal fun editorOptionEntries(namingAvailable: Boolean, showWorkspace: Boolean, hasFile: Boolean): List<EditorOptionEntry> =
    buildList {
        if (namingAvailable) {
            add(EditorOptionEntry.RecordingName)
            if (showWorkspace) add(EditorOptionEntry.ExportName)
        }
        if (hasFile) add(EditorOptionEntry.CloseAudio)
    }

/** Shared production/preview layout, with no player, recorder or publication services. */
@Composable
internal fun EditorWorkspace(
    state: EditorWorkspaceUiState,
    callbacks: EditorWorkspaceCallbacks,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    recordingOptions: @Composable () -> Unit = {},
    recoveryContent: @Composable () -> Unit = {},
    exportOptions: @Composable () -> Unit = {},
) {
    val showWorkspace = showEditorWorkspace(state.file != null)
    var options by remember { mutableStateOf<EditorOptions?>(null) }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box {
            // Every other row is measured first; the waveform receives exactly the space left in the
            // scroll viewport (minimum plot .. half the viewport). When the rows do not fit (small screens,
            // enlarged text, recovery cards) the waveform keeps its minimum and the column scrolls.
            ViewportFillColumn(
                flexMin = waveformRulerHeight() + MIN_PLOT,
                flexMaxFraction = 0.5f,
                spacing = 6.dp,
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                EditorHeader(state, callbacks, onMore = { options = EditorOptions.More })
                FileSourceCard(state, callbacks)
                // Controller errors and recovery notices remain visible outside options dialogs.
                state.closeError?.let {
                    Text(it, color = Amber, style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp, lineHeight = 18.sp))
                }
                recoveryContent()
                if (showWorkspace) {
                    WaveformWorkspace(
                        waveform = state.waveform,
                        durationMs = state.waveform?.durationMs ?: state.file?.durationMs ?: 0L,
                        selStart = state.selStart,
                        selEnd = state.selEnd,
                        playhead = state.playhead,
                        onSelectionChange = callbacks.onSelectionChange,
                        modifier = Modifier.layoutId(FLEX_WAVEFORM),
                    )
                    TransportStrip(
                        enabled = state.transportEnabled,
                        playing = state.playing,
                        selStart = state.selStart,
                        selEnd = state.selEnd,
                        playhead = state.playhead,
                        onSeekBack = callbacks.onSeekBack,
                        onPlayPause = callbacks.onPlayPause,
                        onSeekForward = callbacks.onSeekForward,
                    )
                    StatusInline(state.status, state.busy)
                    ToolGrid(state.toolsEnabled, callbacks.onTool, onMore = { options = EditorOptions.More })
                    OutputCard(state.file, state.outputDirectory, state.toolsEnabled, callbacks.onRingtone)
                } else {
                    StatusInline(state.status, state.sourceBusy)
                    Text("Output: ${state.outputDirectory}", color = TextLo,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp))
                }
            }
        }
    }
    options?.let { page ->
        EditorOptionsDialog(
            title = when (page) {
                EditorOptions.More -> "Editor options"
                EditorOptions.Recording -> "Recording name"
                EditorOptions.Export -> "Export new copy name"
            },
            onDismiss = { options = null },
        ) {
            when (page) {
                EditorOptions.More -> {
                    val entries = editorOptionEntries(state.namingAvailable, showWorkspace, state.file != null)
                    if (entries.isEmpty()) {
                        Text("Open audio or record something new.", style = MaterialTheme.typography.bodyMedium)
                    }
                    entries.forEach { entry ->
                        when (entry) {
                            EditorOptionEntry.RecordingName ->
                                OptionsButton("Recording name", onClick = { options = EditorOptions.Recording })
                            EditorOptionEntry.ExportName ->
                                OptionsButton("Export new copy name", onClick = { options = EditorOptions.Export })
                            EditorOptionEntry.CloseAudio ->
                                OptionsButton("Close audio", enabled = state.closeEnabled, onClick = {
                                    options = null
                                    callbacks.onClose()
                                })
                        }
                    }
                }
                EditorOptions.Recording -> if (state.namingAvailable) recordingOptions()
                EditorOptions.Export -> if (state.namingAvailable && showWorkspace) exportOptions()
            }
        }
    }
}

private val MIN_PLOT = 136.dp
private const val FLEX_WAVEFORM = "editor-waveform"

@Composable
private fun waveformRulerHeight(): Dp = with(LocalDensity.current) { 14.sp.toDp() + 10.dp }

/** Height for each flexible child: what is left of the viewport, clamped to [minPx]..max([minPx], [maxPx]). */
internal fun viewportFlexHeight(viewportPx: Int, usedPx: Int, flexCount: Int, minPx: Int, maxPx: Int): Int =
    if (flexCount <= 0) 0 else ((viewportPx - usedPx) / flexCount).coerceIn(minPx, maxOf(minPx, maxPx))

/**
 * Vertical stack that places children top to bottom with [spacing]. The child tagged
 * [FLEX_WAVEFORM] receives the viewport height left after all other children, clamped to
 * [flexMin]..[flexMaxFraction] of the viewport. Content taller than the viewport overflows into
 * the parent scroll.
 */
@Composable
private fun ViewportFillColumn(
    flexMin: Dp,
    flexMaxFraction: Float,
    spacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val free = Constraints(minWidth = 0, maxWidth = constraints.maxWidth)
        val gap = spacing.roundToPx()
        val fixed = measurables.map { if (it.layoutId == FLEX_WAVEFORM) null else it.measure(free) }
        val flexCount = measurables.count { it.layoutId == FLEX_WAVEFORM }
        val used = fixed.sumOf { it?.height ?: 0 } + gap * (measurables.size - 1).coerceAtLeast(0)
        // Inside verticalScroll the incoming minimum height is the exact viewport in pixels
        // (fillMaxSize before the scroll, minus padding); no Dp round trip, so no 1px overflow.
        val viewportPx = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val flexPx = viewportFlexHeight(viewportPx = viewportPx, usedPx = used, flexCount = flexCount,
            minPx = flexMin.roundToPx(), maxPx = (viewportPx * flexMaxFraction).toInt())
        val placeables = measurables.mapIndexed { i, m ->
            fixed[i] ?: m.measure(free.copy(minHeight = flexPx, maxHeight = flexPx))
        }
        val contentHeight = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
        // Report at least the incoming minimum (fillMaxSize before verticalScroll) so short content
        // stays top-aligned instead of being centred by the parent.
        val height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(constraints.maxWidth, height) {
            var y = 0
            placeables.forEach { it.placeRelative(0, y); y += it.height + gap }
        }
    }
}

private fun fmt(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

private fun fmtDetailed(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%02d:%02d.%03d".format(totalSeconds / 60, totalSeconds % 60, ms % 1000)
}

@Composable
private fun EditorHeader(state: EditorWorkspaceUiState, callbacks: EditorWorkspaceCallbacks, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Accent, modifier = Modifier.size(ControlIcon.Header))
        Spacer(Modifier.width(8.dp))
        Text("Resound", modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 24.sp,
                fontWeight = FontWeight.Medium, letterSpacing = 0.sp))
        IconButton(onClick = callbacks.onOpen, enabled = !state.sourceBusy && !state.recording,
            modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.FolderOpen, "Open audio",
                tint = if (!state.sourceBusy && !state.recording) Accent else TextLo.copy(alpha = 0.45f),
                modifier = Modifier.size(ControlIcon.Toolbar))
        }
        IconButton(onClick = callbacks.onRecord, enabled = !state.sourceBusy, modifier = Modifier.size(48.dp)) {
            Icon(if (state.recording) Icons.Default.Stop else Icons.Default.Mic,
                if (state.recording) "Stop recording" else "Record",
                tint = if (state.sourceBusy) TextLo.copy(alpha = 0.45f) else if (state.recording) Amber else Accent,
                modifier = Modifier.size(ControlIcon.Toolbar))
        }
        IconButton(onClick = onMore, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.MoreHoriz, "More options", tint = Accent, modifier = Modifier.size(ControlIcon.Toolbar))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileSourceCard(state: EditorWorkspaceUiState, callbacks: EditorWorkspaceCallbacks) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = Panel,
        border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AudioFileIcon, null, tint = IconInfo, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.file?.displayName ?: if (state.recording) "Recording audio" else "No audio loaded",
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = TextHi)
                    Text(
                        if (state.recording) "Recording ${fmtDetailed(state.recordElapsedMs)}" else state.file?.let {
                            "${it.sampleRate / 1000f} kHz · ${if (it.isStereo) "Stereo" else "Mono"} · ${fmt(it.durationMs)}"
                        } ?: "Open audio or record something new",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        color = if (state.recording) Amber else TextLo,
                    )
                }
                if (state.file != null) {
                    // Visible close: same controller callback and readiness as the options entry.
                    IconButton(onClick = callbacks.onClose, enabled = state.closeEnabled, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, "Close audio",
                            tint = if (state.closeEnabled) Accent else TextLo.copy(alpha = 0.45f),
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (state.file == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = callbacks.onOpen, enabled = !state.sourceBusy && !state.recording,
                        modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp),
                        colors = resoundOutlinedTextColors(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("Open audio", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(onClick = callbacks.onRecord, enabled = !state.sourceBusy,
                        modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp),
                        colors = resoundOutlinedTextColors(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(if (state.recording) "Stop recording" else "Record",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (state.recording && !state.sourceBusy) Amber else LocalContentColor.current)
                    }
                }
            }
        }
    }
}

/** Tick values describe the same full-duration mapping used by WaveformView. */
private fun rulerTicks(durationMs: Long, intervals: Int): List<Long> {
    if (durationMs <= 0L) return emptyList()
    val desired = durationMs.toDouble() / intervals.coerceAtLeast(1)
    val step = longArrayOf(100, 200, 500, 1_000, 2_000, 5_000, 10_000, 15_000, 30_000,
        60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000)
        .firstOrNull { it >= desired }
        ?: (ceil(desired / 3_600_000) * 3_600_000).toLong().coerceAtLeast(1L)
    return buildList {
        var tick = 0L
        while (tick <= durationMs && size < 16) {
            add(tick)
            if (tick > Long.MAX_VALUE - step) break
            tick += step
        }
    }
}

@Composable
private fun WaveformWorkspace(
    waveform: Waveform?,
    durationMs: Long,
    selStart: Long,
    selEnd: Long,
    playhead: Long,
    onSelectionChange: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val rulerHeight = waveformRulerHeight()
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = InkRaised,
        border = BorderStroke(1.dp, Line)) {
      // Time runs left to right in every locale: ticks and WaveformView draw in absolute
      // coordinates, so the ruler labels must not mirror under RTL.
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val plotWidth = maxWidth
            val labelWidth = 52.dp * fontScale
            val intervals = (maxWidth / (56.dp * fontScale)).toInt().coerceIn(2, 8)
            val ticks = remember(durationMs, intervals) { rulerTicks(durationMs, intervals) }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(rulerHeight)) {
                    Canvas(Modifier.fillMaxSize()) {
                        if (durationMs > 0) {
                            ticks.forEach { tick ->
                                val x = (tick.toDouble() / durationMs * size.width).toFloat()
                                drawLine(Line, Offset(x, size.height - 5.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
                            }
                        }
                    }
                    ticks.forEach { tick ->
                        val fraction = tick.toFloat() / durationMs
                        val start = (plotWidth * fraction - labelWidth / 2)
                            .coerceIn(0.dp, (plotWidth - labelWidth).coerceAtLeast(0.dp))
                        Text(fmt(tick), modifier = Modifier.offset(x = start).width(labelWidth),
                            textAlign = if (tick == 0L) TextAlign.Start else TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp,
                                fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
                            color = TextLo)
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        if (durationMs > 0L && ticks.size > 1) {
                            val minorStep = ticks[1].toDouble() / 4
                            var tick = 0.0
                            while (tick <= durationMs) {
                                val x = (tick / durationMs * size.width).toFloat()
                                drawLine(LineSoft, Offset(x, 0f), Offset(x, size.height), 0.5.dp.toPx())
                                tick += minorStep
                            }
                        }
                        drawLine(LineSoft, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 0.5.dp.toPx())
                    }
                    if (waveform != null && waveform.provenance != WaveformProvenance.UNAVAILABLE) {
                        WaveformView(waveform, selStart, selEnd, playhead, onSelectionChange,
                            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Audio waveform" })
                        if (waveform.provenance == WaveformProvenance.SYNTHETIC) {
                            Text("Synthetic preview", modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = Amber)
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.GraphicEq, null, tint = TextLo, modifier = Modifier.size(24.dp))
                            Text(if (waveform != null) "Waveform unavailable" else "Your waveform will appear here",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                                color = TextMid, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun TransportStrip(
    enabled: Boolean,
    playing: Boolean,
    selStart: Long,
    selEnd: Long,
    playhead: Long,
    onSeekBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
) {
    val enlarged = LocalDensity.current.fontScale > 1.05f
    val endpointStyle = TimecodeStyle.copy(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp)
    val currentStyle = TimecodeStyle.copy(fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line)) {
            if (enlarged) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmtDetailed(playhead), style = currentStyle, color = Accent)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmtDetailed(selStart), style = endpointStyle, color = TextLo)
                        Text(fmtDetailed(selEnd), style = endpointStyle, color = TextLo)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtDetailed(selStart), style = endpointStyle, color = TextLo)
                    Text(fmtDetailed(playhead), style = currentStyle, color = Accent)
                    Text(fmtDetailed(selEnd), style = endpointStyle, color = TextLo)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TransportButton(Icons.Default.Replay10, "Back 10 seconds", enabled, onSeekBack, Modifier.weight(1f))
            TransportButton(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (playing) "Pause" else "Play", enabled, onPlayPause, Modifier.weight(1f), primary = true)
            TransportButton(Icons.Default.Forward10, "Forward 10 seconds", enabled, onSeekForward, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier, primary: Boolean = false) {
    // Primary (play/pause): black surface, thin Steel Blue outline, 30dp icon. Secondary: near-black, neutral outline.
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp), color = if (primary) Ink else Panel,
        border = BorderStroke(1.dp, if (primary && enabled) AccentLine else Line)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (enabled) Accent else TextLo.copy(alpha = 0.45f),
                modifier = Modifier.size(if (primary) ControlIcon.Primary else ControlIcon.Toolbar))
        }
    }
}

@Composable
private fun StatusInline(status: String, busy: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        Text(status, color = TextMid,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp))
    }
}

private data class ToolSpec(val op: EditOp?, val icon: ImageVector, val label: String)

private val toolSpecs = listOf(
    ToolSpec(EditOp.TRIM, Icons.Default.ContentCut, "Trim"),
    ToolSpec(EditOp.MIX, Icons.Default.Layers, "Mix"),
    ToolSpec(EditOp.CONCAT, Icons.Default.AddLink, "Concat"),
    ToolSpec(EditOp.FADE, Icons.Default.ShowChart, "Fade"),
    ToolSpec(EditOp.VOLUME, Icons.Default.VolumeUp, "Volume"),
    ToolSpec(EditOp.SPEED, Icons.Default.Speed, "Speed"),
    ToolSpec(EditOp.PITCH, Icons.Default.MusicNote, "Pitch"),
    ToolSpec(EditOp.EQ, Icons.Default.Equalizer, "EQ"),
    ToolSpec(EditOp.VOCAL, Icons.Default.PersonOff, "Vocal Remove"),
    ToolSpec(EditOp.CONVERT, Icons.Default.SwapHoriz, "Convert"),
    ToolSpec(EditOp.COMPRESS, Icons.Default.Compress, "Compress"),
    ToolSpec(null, Icons.Default.MoreHoriz, "More"),
)

@Composable
private fun ToolGrid(enabled: Boolean, onTap: (EditOp) -> Unit, onMore: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            fontScale >= 1.7f -> 2
            maxWidth < 340.dp || fontScale > 1.05f -> 3
            else -> 4
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            toolSpecs.chunked(columns).forEach { rowItems ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowItems.forEach { item ->
                        ToolTile(item, enabled = if (item.op == null) true else enabled,
                            onClick = { item.op?.let(onTap) ?: onMore() },
                            modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ToolTile(spec: ToolSpec, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 72.dp),
        shape = RoundedCornerShape(8.dp), color = Panel, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(spec.icon, null, tint = if (enabled) Accent else TextLo.copy(alpha = 0.45f), modifier = Modifier.size(ControlIcon.Toolbar))
            Text(spec.label, modifier = Modifier.padding(top = 4.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp,
                    fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
                color = if (enabled) TextHi else TextLo.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun OutputCard(file: AudioFile?, outputDirectory: String, enabled: Boolean, onRingtone: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = Panel, border = BorderStroke(1.dp, Line)) {
        BoxWithConstraints(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (maxWidth < 316.dp || fontScale > 1.05f) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutputDetails(file, outputDirectory)
                    RingtoneButton(enabled, onRingtone)
                }
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { OutputDetails(file, outputDirectory) }
                    RingtoneButton(enabled, onRingtone)
                }
            }
        }
    }
}

@Composable
private fun OutputDetails(file: AudioFile?, outputDirectory: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.AudioFileIcon, null, tint = TextLo, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text("Output", color = TextLo, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp))
            Text(outputDirectory, color = TextHi, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp))
            Text(file?.let { "${it.sampleRate} Hz · ${if (it.isStereo) "Stereo" else "Mono"}" } ?: "Processed files save here",
                color = TextLo, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp))
        }
    }
}

@Composable
private fun RingtoneButton(enabled: Boolean, onRingtone: () -> Unit) {
    ResoundAction("Ringtone", Icons.Default.Notifications, onRingtone, enabled = enabled,
        prominence = ActionProminence.Secondary, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
private fun OptionsButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(8.dp), colors = resoundOutlinedTextColors()) {
        // bodyMedium carries a fixed colour in the theme; use the button's (enabled/disabled) content colour.
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current)
    }
}

@Composable
private fun EditorOptionsDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.55f).coerceAtLeast(96.dp)
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(12.dp), containerColor = Panel,
        titleContentColor = TextHi, textContentColor = TextMid, tonalElevation = 0.dp,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
        })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DraftRecoveryCard(
    drafts: List<RecordingRecoveryAdmission.Candidate>,
    health: Map<RecordingRecoveryAdmission.Candidate, RecoveryHealth>,
    enabled: Boolean,
    onLoad: (RecordingRecoveryAdmission.Candidate) -> Unit,
    onSave: (RecordingRecoveryAdmission.Candidate) -> Unit,
    onDiscard: (RecordingRecoveryAdmission.Candidate) -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Panel, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Unsaved recordings", color = TextHi, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp))
            drafts.forEach { f ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(f.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp))
                    Text((health[f] ?: RecoveryHealth.UNVERIFIED).label, color = TextMid,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { onLoad(f) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp), colors = resoundOutlinedTextColors()) { Text("Open") }
                        OutlinedButton(onClick = { onSave(f) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp), colors = resoundOutlinedTextColors()) { Text("Save") }
                        OutlinedButton(onClick = { onDiscard(f) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp), colors = resoundOutlinedTextColors()) { Text("Discard") }
                    }
                }
            }
        }
    }
}

