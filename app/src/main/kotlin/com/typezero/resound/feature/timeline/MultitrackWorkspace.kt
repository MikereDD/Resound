/*
 * file:    MultitrackWorkspace.kt
 * desc:    Display-only Multitrack workstation: compact header and status, a shared ruler and
 *          stacked track lanes filling the screen, and one compact toolbar. Arrangement state,
 *          persistence, clip import and mixdown stay in TimelineScreen.
 */
package com.typezero.resound.feature.timeline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typezero.resound.ui.theme.Amber
import com.typezero.resound.ui.theme.Ink
import com.typezero.resound.ui.theme.InkRaised
import com.typezero.resound.ui.theme.Line
import com.typezero.resound.ui.theme.LineSoft
import com.typezero.resound.ui.theme.Panel
import com.typezero.resound.ui.theme.PanelHi
import com.typezero.resound.ui.components.ActionProminence
import com.typezero.resound.ui.components.ResoundAction
import com.typezero.resound.ui.theme.Accent
import com.typezero.resound.ui.theme.AccentLine
import com.typezero.resound.ui.theme.ControlIcon
import com.typezero.resound.ui.theme.Signal
import com.typezero.resound.ui.theme.TextHi
import com.typezero.resound.ui.theme.TextLo
import com.typezero.resound.ui.theme.TextMid
import com.typezero.resound.ui.theme.TimecodeStyle
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal val TRACK_HEADER_WIDTH = 108.dp
/** Time origin shared by ruler and lanes: header column plus its 1dp divider. */
internal val LANE_START = TRACK_HEADER_WIDTH + 1.dp
private val MIN_TIMELINE = 216.dp
private const val FLEX_TIMELINE = "multitrack-timeline"

/** Display-only inputs. Ownership, recovery admission and persistence stay in TimelineScreen/TimelineSession. */
internal data class MultitrackUiState(
    val tracks: List<Track> = emptyList(),
    val selectedClip: Long? = null,
    val pxPerSec: Float = 24f,
    val status: String = "Add a track, then place audio clips on the timeline.",
    val busy: Boolean = false,
    val recoveryReady: Boolean = true,
    val draftStatus: String = "",
    val persistenceError: String? = null,
    val namingNotice: String? = null,
    val namingAvailable: Boolean = true,
)

/** Existing controller actions, forwarded unchanged. */
internal data class MultitrackCallbacks(
    val onAddTrack: () -> Unit = {},
    val onExportMix: () -> Unit = {},
    val onZoomOut: () -> Unit = {},
    val onZoomIn: () -> Unit = {},
    val onAddClip: (trackId: Long) -> Unit = {},
    val onToggleMute: (trackId: Long) -> Unit = {},
    val onSelectClip: (clipId: Long) -> Unit = {},
    val onMove: (trackId: Long, clipId: Long, deltaMs: Long) -> Unit = { _, _, _ -> },
    val onTrimIn: (trackId: Long, clipId: Long, deltaMs: Long) -> Unit = { _, _, _ -> },
    val onTrimOut: (trackId: Long, clipId: Long, deltaMs: Long) -> Unit = { _, _, _ -> },
)

internal fun multitrackEditingEnabled(recoveryReady: Boolean, busy: Boolean): Boolean = recoveryReady && !busy
internal fun exportMixEnabled(editingEnabled: Boolean, tracks: List<Track>): Boolean =
    exportMixEnabled(editingEnabled, tracks.sumOf { it.clips.size })
internal fun exportMixEnabled(editingEnabled: Boolean, clipCount: Int): Boolean = editingEnabled && clipCount > 0
internal fun zoomedOut(pxPerSec: Float): Float = (pxPerSec / 1.35f).coerceAtLeast(6f)
internal fun zoomedIn(pxPerSec: Float): Float = (pxPerSec * 1.35f).coerceAtMost(240f)
private val RULER_STEPS = intArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600)
/** Smallest standard step (seconds) whose on-screen spacing is at least [minSpacingPx]: labels never collide. */
internal fun rulerLabelStepSeconds(pxPerSec: Float, minSpacingPx: Float): Int =
    RULER_STEPS.firstOrNull { it * pxPerSec >= minSpacingPx } ?: RULER_STEPS.last()
/** Width available to a ruler label starting at [labelLeftPx]; null when it would start at or past the edge. */
internal fun rulerLabelRoomPx(canvasWidthPx: Float, labelLeftPx: Float): Float? =
    (canvasWidthPx - labelLeftPx).takeIf { it >= 1f }
internal fun timelineDisplayDurationMs(tracks: List<Track>): Long = max(Timeline(tracks).durationMs, 30_000L)
/** The timeline takes everything left in the viewport, never less than [minPx] (then the screen scrolls). */
internal fun timelineFlexHeight(viewportPx: Int, usedPx: Int, minPx: Int): Int = (viewportPx - usedPx).coerceAtLeast(minPx)
internal fun multitrackSummary(tracks: List<Track>): String {
    val clips = tracks.sumOf { it.clips.size }
    return "${tracks.size} ${if (tracks.size == 1) "track" else "tracks"} · $clips ${if (clips == 1) "clip" else "clips"}"
}

@Composable
internal fun MultitrackWorkspace(
    state: MultitrackUiState,
    callbacks: MultitrackCallbacks,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    laneScrollState: ScrollState = rememberScrollState(),
    horizontalScroll: ScrollState = rememberScrollState(),
    exportNaming: @Composable () -> Unit = {},
    diagnostics: @Composable () -> Unit = {},
) {
    val editingEnabled = multitrackEditingEnabled(state.recoveryReady, state.busy)
    var showOptions by rememberSaveable { mutableStateOf(false) }
    val selected = state.tracks.flatMap { it.clips }.firstOrNull { it.id == state.selectedClip }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        TimelineFillColumn(
            flexMin = MIN_TIMELINE,
            spacing = 6.dp,
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            MultitrackHeader(state.tracks, showMore = state.namingAvailable, onMore = { showOptions = true })
            MultitrackStatus(state, busy = state.busy || !state.recoveryReady, diagnostics = diagnostics)
            TimelinePanel(state, callbacks, editingEnabled, laneScrollState, horizontalScroll,
                Modifier.layoutId(FLEX_TIMELINE))
            // Always present (placeholder when nothing is selected): selecting a clip mid-drag must
            // not resize the timeline under the finger.
            SelectedClipReadout(selected)
            MultitrackToolbar(state, callbacks, editingEnabled)
        }
    }
    if (showOptions) {
        MultitrackOptionsDialog(state.draftStatus, onDismiss = { showOptions = false }, exportNaming = exportNaming)
    }
}

/** Column whose [FLEX_TIMELINE] child receives the viewport height left by its siblings. */
@Composable
private fun TimelineFillColumn(flexMin: Dp, spacing: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val free = Constraints(minWidth = 0, maxWidth = constraints.maxWidth)
        val gap = spacing.roundToPx()
        val fixed = measurables.map { if (it.layoutId == FLEX_TIMELINE) null else it.measure(free) }
        val used = fixed.sumOf { it?.height ?: 0 } + gap * (measurables.size - 1).coerceAtLeast(0)
        val viewportPx = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val flexPx = timelineFlexHeight(viewportPx, used, flexMin.roundToPx())
        val placeables = measurables.mapIndexed { i, m ->
            fixed[i] ?: m.measure(free.copy(minHeight = flexPx, maxHeight = flexPx))
        }
        val contentHeight = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            placeables.forEach { it.placeRelative(0, y); y += it.height + gap }
        }
    }
}

@Composable
private fun MultitrackHeader(tracks: List<Track>, showMore: Boolean, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Tune, contentDescription = null, tint = Accent, modifier = Modifier.size(ControlIcon.Header))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Multitrack", maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextHi,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = 0.sp))
            Text(multitrackSummary(tracks), color = TextLo,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp))
        }
        if (showMore) {
            IconButton(onClick = onMore, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreHoriz, "More options", tint = Accent, modifier = Modifier.size(ControlIcon.Toolbar))
            }
        }
    }
}

@Composable
private fun MultitrackStatus(state: MultitrackUiState, busy: Boolean, diagnostics: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (busy) Amber else Accent))
            Spacer(Modifier.width(8.dp))
            Text(state.status, color = TextMid, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp))
        }
        Text(state.draftStatus, color = TextLo,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp))
        state.persistenceError?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp))
        }
        state.namingNotice?.let {
            Text(it, color = Amber, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp))
        }
        diagnostics()
    }
}

@Composable
private fun TimelinePanel(
    state: MultitrackUiState,
    callbacks: MultitrackCallbacks,
    enabled: Boolean,
    laneScrollState: ScrollState,
    horizontalScroll: ScrollState,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val displayDuration = timelineDisplayDurationMs(state.tracks)
    val pxPerMs = state.pxPerSec / 1000f
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = InkRaised,
        border = BorderStroke(1.dp, Line)) {
        // Time runs left to right in every locale; lanes, ruler and clips share absolute x positions.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Ruler and lanes share one time origin (LANE_START) and one viewport width; the width is
                // floored to whole pixels so an arrangement that fits never scrolls by a rounding pixel.
                val availablePx = with(density) { (maxWidth - LANE_START).toPx().toInt().coerceAtLeast(1).toFloat() }
                val contentPx = max(availablePx, displayDuration * pxPerMs)
                val contentDp = with(density) { contentPx.toDp() }
                // Few tracks: lanes grow (up to 120dp) into the timeline area; many: base height and scroll.
                val base = baseLaneHeight()
                val laneHeight = if (state.tracks.isEmpty()) base else
                    ((maxHeight - rulerHeight() - 1.dp) / state.tracks.size - 1.dp).coerceIn(base, maxOf(base, 120.dp))
                Column(Modifier.fillMaxSize()) {
                    TimelineRuler(displayDuration, contentDp, state.pxPerSec, availablePx, horizontalScroll)
                    HorizontalDivider(color = Line)
                    if (state.tracks.isEmpty()) {
                        EmptyTimeline(Modifier.weight(1f))
                    } else {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(laneScrollState)) {
                            state.tracks.forEachIndexed { index, track ->
                                TrackLane(
                                    track = track,
                                    accent = trackAccent(index),
                                    contentDp = contentDp,
                                    pxPerMs = pxPerMs,
                                    scrollState = horizontalScroll,
                                    selectedClip = state.selectedClip,
                                    enabled = enabled,
                                    callbacks = callbacks,
                                    laneHeight = laneHeight,
                                )
                                HorizontalDivider(color = LineSoft)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rulerHeight(): Dp = with(LocalDensity.current) { 14.sp.toDp() + 12.dp }

/** Name line + one 48dp control row; grows with the font scale instead of clipping. */
@Composable
private fun baseLaneHeight(): Dp = with(LocalDensity.current) { 16.sp.toDp() } + 56.dp

@Composable
private fun TimelineRuler(durationMs: Long, contentDp: Dp, pxPerSec: Float, viewportPx: Float, scrollState: ScrollState) {
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.sp, color = TextMid)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Labels are never closer than the widest label plus a gap, at any zoom or font scale.
    val minLabelPx = remember(labelStyle, density, measurer) {
        measurer.measure("000:00", labelStyle).size.width + with(density) { 12.dp.toPx() }
    }
    val labelStep = rulerLabelStepSeconds(pxPerSec, minLabelPx)
    val tickStep = rulerLabelStepSeconds(pxPerSec, with(density) { 6.dp.toPx() })
    Row(Modifier.fillMaxWidth().height(rulerHeight()), verticalAlignment = Alignment.CenterVertically) {
        Text("TIME", modifier = Modifier.width(TRACK_HEADER_WIDTH).padding(start = 10.dp), style = labelStyle,
            color = TextLo, maxLines = 1)
        Box(Modifier.width(1.dp).fillMaxHeight().background(Line))
        Box(Modifier.weight(1f).fillMaxHeight().background(Ink).horizontalScroll(scrollState)) {
            // One Canvas; only the visible window (plus a label of margin) is drawn, so long
            // arrangements cost the same as short ones. Reading the scroll offset here only redraws.
            Canvas(Modifier.width(contentDp).fillMaxHeight()) {
                val totalSeconds = ceil(durationMs / 1000.0).toInt()
                val first = ((scrollState.value - minLabelPx) / pxPerSec).toInt().coerceAtLeast(0)
                val last = ((scrollState.value + viewportPx + minLabelPx) / pxPerSec).toInt().coerceAtMost(totalSeconds)
                var second = first - first % tickStep
                while (second <= last) {
                    val x = second * pxPerSec
                    drawLine(LineSoft, Offset(x, size.height * 0.72f), Offset(x, size.height), 1.dp.toPx())
                    second += tickStep
                }
                second = first - first % labelStep
                while (second <= last) {
                    val x = second * pxPerSec
                    drawLine(TextLo, Offset(x, size.height * 0.45f), Offset(x, size.height), 1.dp.toPx())
                    // The last label can sit exactly at the content end; drawText would then receive a
                    // negative width and throw, so labels are only drawn into the room they have.
                    val left = x + 3.dp.toPx()
                    rulerLabelRoomPx(size.width, left)?.let { room ->
                        drawText(measurer, formatTime(second * 1000L), Offset(left, 1.dp.toPx()), labelStyle,
                            overflow = TextOverflow.Clip, softWrap = false, maxLines = 1,
                            size = Size(room, (size.height - 1.dp.toPx()).coerceAtLeast(1f)))
                    }
                    second += labelStep
                }
            }
        }
    }
}

@Composable
private fun EmptyTimeline(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Outlined.AudioFile, contentDescription = null, tint = TextLo)
        Text("Your mix starts here", style = MaterialTheme.typography.titleMedium, color = TextHi, textAlign = TextAlign.Center)
        Text("Add a track, then add one or more audio clips.", style = MaterialTheme.typography.bodyMedium,
            color = TextLo, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TrackLane(
    track: Track,
    accent: Color,
    contentDp: Dp,
    pxPerMs: Float,
    scrollState: ScrollState,
    selectedClip: Long?,
    enabled: Boolean,
    callbacks: MultitrackCallbacks,
    laneHeight: Dp,
) {
    val hasSelection = track.clips.any { it.id == selectedClip }
    Row(Modifier.fillMaxWidth().height(laneHeight)) {
        Row(Modifier.width(TRACK_HEADER_WIDTH).fillMaxHeight().background(if (hasSelection) PanelHi else Panel)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (track.muted) TextLo else accent))
            Column(Modifier.fillMaxHeight().padding(start = 6.dp, top = 4.dp)) {
                Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (track.muted) TextLo else TextHi,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp, lineHeight = 16.sp,
                        fontWeight = if (hasSelection) FontWeight.SemiBold else FontWeight.Medium, letterSpacing = 0.sp))
                Row {
                    LaneControl("+", "Add clip to ${track.name}", active = false, enabled = enabled) {
                        callbacks.onAddClip(track.id)
                    }
                    LaneControl("M", if (track.muted) "Unmute ${track.name}" else "Mute ${track.name}",
                        active = track.muted, enabled = enabled) { callbacks.onToggleMute(track.id) }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(if (hasSelection) Accent else Line))
        Box(Modifier.weight(1f).fillMaxHeight().background(Ink).horizontalScroll(scrollState)) {
            Canvas(modifier = Modifier.width(contentDp).height(laneHeight)) {
                val seconds = (size.width / (pxPerMs * 1000f)).toInt().coerceAtLeast(0)
                for (second in 0..seconds) {
                    val x = second * 1000f * pxPerMs
                    drawLine(LineSoft, Offset(x, 0f), Offset(x, size.height), 1f)
                }
                drawLine(Line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
            }
            Box(modifier = Modifier.width(contentDp).height(laneHeight)) {
                if (track.clips.isEmpty()) {
                    Text("Empty track · tap + to add a clip", color = TextLo, maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp))
                }
                track.clips.forEach { clip ->
                    ClipBox(
                        clip = clip,
                        pxPerMs = pxPerMs,
                        accent = accent,
                        muted = track.muted,
                        selected = selectedClip == clip.id,
                        onSelect = { callbacks.onSelectClip(clip.id) },
                        onMove = { callbacks.onMove(track.id, clip.id, it) },
                        onTrimIn = { callbacks.onTrimIn(track.id, clip.id, it) },
                        onTrimOut = { callbacks.onTrimOut(track.id, clip.id, it) },
                        laneHeight = laneHeight,
                    )
                }
            }
        }
    }
}

/** Compact visual pill inside a full 48dp touch target. */
@Composable
private fun LaneControl(label: String, description: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (active) Amber else PanelHi,
            contentColor = if (active) Ink else if (enabled) Accent else TextLo.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, if (active) Amber else Line),
            modifier = Modifier.widthIn(min = 36.dp).heightIn(min = 28.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                // Explicit colour: the theme's default text style carries TextHi, which would override the
                // Surface content colour and make enabled and disabled lanes look identical.
                Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = LocalContentColor.current,
                    modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

@Composable
private fun SelectedClipReadout(clip: Clip?) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = Panel,
        border = BorderStroke(1.dp, if (clip != null) AccentLine else Line)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClipMetric("START", clip?.let { formatTime(it.startMs) })
            ClipMetric("DURATION", clip?.let { formatTime(it.lengthMs) })
            ClipMetric("END", clip?.let { formatTime(it.startMs + it.lengthMs) })
        }
    }
}

@Composable
private fun ClipMetric(label: String, value: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp), color = TextLo)
        // "-:--" while no clip is selected keeps the row's height constant.
        Text(value ?: "-:--", style = TimecodeStyle.copy(fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
            color = if (value != null) TextHi else TextLo)
    }
}

@Composable
private fun MultitrackToolbar(state: MultitrackUiState, callbacks: MultitrackCallbacks, enabled: Boolean) {
    val exportEnabled = exportMixEnabled(enabled, state.tracks)
    val oneRow = toolbarFitsOneRow()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = Panel, border = BorderStroke(1.dp, Line)) {
        BoxWithConstraints(Modifier.padding(6.dp)) {
            if (!oneRow(maxWidth)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AddTrackButton(enabled, callbacks.onAddTrack, Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        ZoomControls(state.pxPerSec, enabled, callbacks)
                    }
                    ExportMixButton(exportEnabled, callbacks.onExportMix, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TOOLBAR_GAP)) {
                    AddTrackButton(enabled, callbacks.onAddTrack)
                    ZoomControls(state.pxPerSec, enabled, callbacks)
                    Spacer(Modifier.weight(1f))
                    ExportMixButton(exportEnabled, callbacks.onExportMix)
                }
            }
        }
    }
}

private val TOOLBAR_GAP = 4.dp
// Toolbar keeps the approved Phase 3 label sizes so the reference 412dp width stays on one row with the 30dp
// Export Mix icon; Add Track steps its icon down to 24dp for the same reason.
private val ADD_TRACK_ICON = 24.dp
private val ADD_TRACK_LABEL = 12.sp
private val EXPORT_LABEL = 13.sp
private val ADD_TRACK_PADDING = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
private val EXPORT_PADDING = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
/** Widest zoom label (zoom is capped at 240 px/s by zoomedIn); reserving it keeps the controls still while zooming. */
private const val WIDEST_ZOOM_LABEL = "240 px/s"

/**
 * One toolbar row only when Add Track, the zoom group and Export Mix fit at their full icon sizes; otherwise the
 * toolbar wraps (Export Mix gets its own full-width row) instead of shrinking or clipping. Text is measured.
 */
@Composable
private fun zoomLabelWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp)
    return remember(measurer, density, style) { with(density) { measurer.measure(WIDEST_ZOOM_LABEL, style).size.width.toDp() + 2.dp } }
}

@Composable
private fun toolbarFitsOneRow(): (Dp) -> Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val label = MaterialTheme.typography.labelLarge.copy(lineHeight = 18.sp, letterSpacing = 0.sp)
    val zoom = zoomLabelWidth()
    val required = remember(density, measurer, label, zoom) {
        with(density) {
            val add = measurer.measure("Add Track", label.copy(fontSize = ADD_TRACK_LABEL, fontWeight = FontWeight.Medium)).size.width.toDp()
            val export = measurer.measure("Export Mix", label.copy(fontSize = EXPORT_LABEL, fontWeight = FontWeight.SemiBold)).size.width.toDp()
            (8.dp + ADD_TRACK_ICON + 8.dp + add + 8.dp) + (48.dp + zoom + 48.dp) +
                (10.dp + ControlIcon.Primary + 8.dp + export + 10.dp) + TOOLBAR_GAP * 3 + 1.dp // 1dp rounding margin
        }
    }
    return { available -> available >= required }
}

@Composable
private fun AddTrackButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ResoundAction("Add Track", Icons.AutoMirrored.Outlined.PlaylistAdd, onClick, modifier, enabled = enabled,
        prominence = ActionProminence.Secondary, iconSize = ADD_TRACK_ICON, contentPadding = ADD_TRACK_PADDING,
        labelSize = ADD_TRACK_LABEL)
}

@Composable
private fun ZoomControls(pxPerSec: Float, enabled: Boolean, callbacks: MultitrackCallbacks) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ZoomButton(Icons.Outlined.Remove, "Zoom out", enabled, callbacks.onZoomOut)
        Text("${pxPerSec.roundToInt()} px/s", color = TextMid, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.width(zoomLabelWidth()),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp))
        ZoomButton(Icons.Outlined.Add, "Zoom in", enabled, callbacks.onZoomIn)
    }
}

@Composable
private fun ZoomButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(8.dp),
        color = InkRaised, border = BorderStroke(1.dp, Line)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = if (enabled) Accent else TextLo.copy(alpha = 0.45f),
                modifier = Modifier.size(ControlIcon.Navigation))
        }
    }
}

/** Reference primary action: black surface, thin Steel Blue outline, 30dp Steel Blue icon, light-grey label. */
@Composable
private fun ExportMixButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ResoundAction("Export Mix", Icons.Outlined.FileUpload, onClick, modifier, enabled = enabled,
        prominence = ActionProminence.Primary, contentPadding = EXPORT_PADDING, labelSize = EXPORT_LABEL)
}

@Composable
private fun MultitrackOptionsDialog(draftStatus: String, onDismiss: () -> Unit, exportNaming: @Composable () -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.6f).coerceAtLeast(120.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        containerColor = Panel,
        titleContentColor = TextHi,
        textContentColor = TextMid,
        tonalElevation = 0.dp,
        title = { Text("Multitrack options", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                exportNaming()
                Text(draftStatus, color = TextLo, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") } },
    )
}

@Composable
private fun ClipBox(
    clip: Clip,
    pxPerMs: Float,
    accent: Color,
    muted: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onMove: (Long) -> Unit,
    onTrimIn: (Long) -> Unit,
    onTrimOut: (Long) -> Unit,
    laneHeight: Dp,
) {
    val density = LocalDensity.current
    val scale = rememberUpdatedState(pxPerMs)
    val moveCallback = rememberUpdatedState(onMove)
    val trimInCallback = rememberUpdatedState(onTrimIn)
    val trimOutCallback = rememberUpdatedState(onTrimOut)
    val selectCallback = rememberUpdatedState(onSelect)
    var mode by remember { mutableStateOf(0) }

    val xPx = (clip.startMs * pxPerMs).roundToInt()
    val widthDp = with(density) { (clip.lengthMs * pxPerMs).coerceAtLeast(24f).toDp() }
    val topColor = if (muted) Color(0xFF44525D) else accent
    val bottomColor = if (muted) Color(0xFF2D3942) else accent.copy(alpha = 0.55f)

    Box(
        modifier = Modifier
            .offset { IntOffset(xPx, 0) }
            .width(widthDp)
            .height(laneHeight)
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(9.dp))
            .pointerInput(clip.id) {
                val edge = 30.dp.toPx()
                // Horizontal-only: move/trim are horizontal, and vertical swipes on a clip must reach
                // the lane and screen scrollers instead of being consumed here.
                detectHorizontalDragGestures(
                    onDragStart = { position ->
                        selectCallback.value()
                        mode = when {
                            position.x < edge -> 1
                            position.x > size.width - edge -> 2
                            else -> 0
                        }
                    },
                ) { change, dragX ->
                    change.consume()
                    val currentScale = scale.value
                    if (currentScale > 0f) {
                        val delta = (dragX / currentScale).toLong()
                        when (mode) {
                            1 -> trimInCallback.value(delta)
                            2 -> trimOutCallback.value(delta)
                            else -> moveCallback.value(delta)
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(color = PanelHi, cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()))
            val waveform = clip.waveform
            val count = waveform.bucketCount
            val duration = clip.source.durationMs
            if (count > 0 && duration > 0) {
                val startIndex = ((clip.sourceInMs.toFloat() / duration) * count).toInt().coerceIn(0, count)
                val endIndex = ((clip.sourceOutMs.toFloat() / duration) * count).toInt().coerceIn(startIndex, count)
                val visible = (endIndex - startIndex).coerceAtLeast(1)
                val midY = size.height / 2f
                val step = size.width / visible
                val path = Path().apply {
                    moveTo(0f, midY)
                    for (index in 0 until visible) {
                        lineTo(index * step, midY - waveform.maxs[startIndex + index] * midY)
                    }
                    for (index in visible - 1 downTo 0) {
                        lineTo(index * step, midY - waveform.mins[startIndex + index] * midY)
                    }
                    close()
                }
                drawPath(path, brush = Brush.verticalGradient(listOf(topColor, bottomColor)))
            }
            val border = if (selected) Accent else accent.copy(alpha = 0.55f)
            drawRoundRect(
                color = border,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
                style = Stroke(width = if (selected) 4f else 2f),
            )
            if (selected) {
                val handleWidth = 12.dp.toPx()
                drawRoundRect(
                    color = Accent,
                    topLeft = Offset(0f, 0f),
                    size = Size(handleWidth, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                )
                drawRoundRect(
                    color = Accent,
                    topLeft = Offset(size.width - handleWidth, 0f),
                    size = Size(handleWidth, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                )
                val grip = Color.White.copy(alpha = 0.72f)
                val centerY = size.height / 2f
                for (offset in listOf(-6f, 0f, 6f)) {
                    drawCircle(grip, radius = 1.6.dp.toPx(), center = Offset(handleWidth / 2f, centerY + offset))
                    drawCircle(grip, radius = 1.6.dp.toPx(), center = Offset(size.width - handleWidth / 2f, centerY + offset))
                }
            }
        }
        Text(
            if (clip.sourceAvailable) clip.source.displayName else "Unavailable: ${clip.source.displayName}",
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun trackAccent(index: Int): Color = when (index % 6) {
    0 -> Signal
    1 -> Color(0xFF57A6FF)
    2 -> Amber
    3 -> Color(0xFFE26D9F)
    4 -> Color(0xFFA77BF3)
    else -> Color(0xFF65C97A)
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
