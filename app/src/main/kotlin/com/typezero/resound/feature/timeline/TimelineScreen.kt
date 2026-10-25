/*
 * file:    TimelineScreen.kt
 * author:  Mike Redd (typezero)
 * version: 0.8.0-dev.4
 * desc:    Premium multitrack workspace with a shared ruler, compact track
 *          controls, zoomable lanes, draggable/trim-capable clips, and a
 *          prominent mixdown action.
 */
package com.typezero.resound.feature.timeline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.typezero.resound.core.audio.AudioFiles
import com.typezero.resound.core.audio.WaveformExtractor
import com.typezero.resound.core.ffmpeg.FFmpegRunner
import com.typezero.resound.core.io.Outputs
import com.typezero.resound.feature.effects.Effects
import com.typezero.resound.ui.components.ResoundCard
import com.typezero.resound.ui.theme.Amber
import com.typezero.resound.ui.theme.Ink
import com.typezero.resound.ui.theme.Line
import com.typezero.resound.ui.theme.LineSoft
import com.typezero.resound.ui.theme.Panel
import com.typezero.resound.ui.theme.PanelHi
import com.typezero.resound.ui.theme.Signal
import com.typezero.resound.ui.theme.SignalDeep
import com.typezero.resound.ui.theme.TextLo
import com.typezero.resound.ui.theme.TextMid
import com.typezero.resound.ui.theme.TimecodeStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private const val MIN_CLIP_MS = 200L
private const val LANE_H = 86
private val TRACK_LABEL_WIDTH = 94.dp

@Composable
fun TimelineScreen(
    waveformExtractor: WaveformExtractor,
    ffmpeg: FFmpegRunner,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    var idCounter by remember { mutableLongStateOf(1L) }
    var selectedClip by remember { mutableStateOf<Long?>(null) }
    var addTrackId by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Add a track, then place audio clips on the timeline.") }
    var pxPerSec by remember { mutableFloatStateOf(24f) }

    fun nextId(): Long = idCounter++
    fun updateTrack(id: Long, transform: (Track) -> Track) {
        tracks = tracks.map { if (it.id == id) transform(it) else it }
    }
    fun updateClip(trackId: Long, clipId: Long, transform: (Clip) -> Clip) {
        updateTrack(trackId) { track ->
            track.copy(clips = track.clips.map { if (it.id == clipId) transform(it) else it })
        }
    }

    val timeline = Timeline(tracks)
    val displayDuration = max(timeline.durationMs, 30_000L)
    val pxPerMs = pxPerSec / 1000f
    val horizontalScroll = rememberScrollState()

    val clipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val trackId = addTrackId
        addTrackId = null
        if (uri == null || trackId == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                busy = true
                status = "Adding clip…"
                val audio = AudioFiles.readMetadata(context, uri)
                val waveform = waveformExtractor.extract(audio, targetBuckets = 600)
                updateTrack(trackId) { it.copy(clips = it.clips + Clip(nextId(), audio, waveform, startMs = 0L)) }
                status = "Added ${audio.displayName}"
            } catch (t: Throwable) {
                status = "Could not add clip: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    fun exportMix() {
        scope.launch {
            val owned = mutableListOf<File>()
            try {
                busy = true
                status = "Mixing tracks…"
                val active = tracks.flatMap { track ->
                    if (track.muted) emptyList() else track.clips.map { track to it }
                }
                if (active.isEmpty()) {
                    status = "Add a clip to an unmuted track first."
                    return@launch
                }
                val inputs = active.map { (track, clip) ->
                    Effects.TimelineInput(
                        path = AudioFiles.resolveToCache(context, clip.source)
                            .also { owned += it }.absolutePath,
                        startMs = clip.startMs,
                        inMs = clip.sourceInMs,
                        outMs = clip.sourceOutMs,
                        volume = track.volume,
                    )
                }
                val temp = Outputs.newTempFile(context, "mix", "m4a")
                    .also { owned += it }
                val result = ffmpeg.run(Effects.mixTimeline(inputs, temp.absolutePath))
                status = if (result.isSuccess) {
                    val published = Outputs.publishToMusic(context, temp, Outputs.exportName("mix", "m4a"), "m4a")
                    "Mix saved to ${published.displayPath}"
                } else {
                    "Mix failed (rc=${result.returnCode})"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                status = "Mix failed: ${t.message}"
            } finally {
                owned.forEach { it.delete() }
                busy = false
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MultitrackHeader(trackCount = tracks.size, clipCount = tracks.sumOf { it.clips.size })

            ResoundCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { tracks = tracks + Track(nextId(), "Track ${tracks.size + 1}") },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).height(60.dp),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add Track")
                        }
                        OutlinedButton(
                            onClick = { exportMix() },
                            enabled = !busy && tracks.any { it.clips.isNotEmpty() },
                            modifier = Modifier.weight(1f).height(60.dp),
                            border = BorderStroke(1.dp, Signal),
                        ) {
                            Icon(Icons.Outlined.FileUpload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Export Mix")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("ZOOM", style = MaterialTheme.typography.labelLarge, color = TextLo)
                            Text("${pxPerSec.roundToInt()} px/sec", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ZoomButton(Icons.Outlined.Remove, "Zoom out") {
                                pxPerSec = (pxPerSec / 1.35f).coerceAtLeast(6f)
                            }
                            ZoomButton(Icons.Outlined.Add, "Zoom in") {
                                pxPerSec = (pxPerSec * 1.35f).coerceAtMost(240f)
                            }
                        }
                    }
                }
            }

            StatusStrip(status = status, busy = busy)

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val availablePx = with(density) { (maxWidth - TRACK_LABEL_WIDTH).toPx().coerceAtLeast(1f) }
                val contentPx = max(availablePx, displayDuration * pxPerMs)
                val contentDp = with(density) { contentPx.toDp() }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimelineRuler(
                        durationMs = displayDuration,
                        contentDp = contentDp,
                        pxPerMs = pxPerMs,
                        scrollState = horizontalScroll,
                    )

                    if (tracks.isEmpty()) {
                        EmptyTimeline()
                    } else {
                        tracks.forEachIndexed { index, track ->
                            TrackLane(
                                track = track,
                                accent = trackAccent(index),
                                contentDp = contentDp,
                                pxPerMs = pxPerMs,
                                scrollState = horizontalScroll,
                                selectedClip = selectedClip,
                                enabled = !busy,
                                onAddClip = {
                                    addTrackId = track.id
                                    clipPicker.launch(arrayOf("audio/*", "video/*"))
                                },
                                onToggleMute = { updateTrack(track.id) { it.copy(muted = !it.muted) } },
                                onSelectClip = { selectedClip = it },
                                onMove = { clipId, delta ->
                                    updateClip(track.id, clipId) { it.copy(startMs = (it.startMs + delta).coerceAtLeast(0L)) }
                                },
                                onTrimIn = { clipId, delta ->
                                    updateClip(track.id, clipId) { clip ->
                                        val newIn = (clip.sourceInMs + delta)
                                            .coerceIn(0L, clip.sourceOutMs - MIN_CLIP_MS)
                                        val adjustment = newIn - clip.sourceInMs
                                        clip.copy(
                                            sourceInMs = newIn,
                                            startMs = (clip.startMs + adjustment).coerceAtLeast(0L),
                                        )
                                    }
                                },
                                onTrimOut = { clipId, delta ->
                                    updateClip(track.id, clipId) { clip ->
                                        clip.copy(
                                            sourceOutMs = (clip.sourceOutMs + delta)
                                                .coerceIn(clip.sourceInMs + MIN_CLIP_MS, clip.source.durationMs),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun MultitrackHeader(trackCount: Int, clipCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Signal.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, Signal.copy(alpha = 0.45f)),
            modifier = Modifier.width(58.dp).height(58.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = Signal)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Multitrack", style = MaterialTheme.typography.headlineSmall)
            Text("Arrange · trim · mix", style = MaterialTheme.typography.bodyMedium)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("$trackCount TRACKS", style = MaterialTheme.typography.labelLarge, color = TextLo)
            Text("$clipCount clips", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ZoomButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.width(54.dp).height(42.dp),
        contentPadding = ButtonDefaults.ContentPadding,
        border = BorderStroke(1.dp, Line),
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun StatusStrip(status: String, busy: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Panel,
        border = BorderStroke(1.dp, if (busy) Signal else Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (busy) Amber else Signal),
            )
            Spacer(Modifier.width(10.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}

@Composable
private fun TimelineRuler(
    durationMs: Long,
    contentDp: androidx.compose.ui.unit.Dp,
    pxPerMs: Float,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            "TIME",
            modifier = Modifier.width(TRACK_LABEL_WIDTH).padding(start = 4.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = TextLo,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Panel)
                .horizontalScroll(scrollState),
        ) {
            Canvas(modifier = Modifier.width(contentDp).height(42.dp)) {
                val totalSeconds = ceil(durationMs / 1000.0).toInt()
                val majorEvery = when {
                    pxPerMs * 1000f >= 100f -> 1
                    pxPerMs * 1000f >= 35f -> 5
                    else -> 10
                }
                for (second in 0..totalSeconds) {
                    val x = second * 1000f * pxPerMs
                    val major = second % majorEvery == 0
                    drawLine(
                        color = if (major) TextLo else LineSoft,
                        start = Offset(x, if (major) 12f else 25f),
                        end = Offset(x, size.height),
                        strokeWidth = if (major) 2f else 1f,
                    )
                    if (major) {
                        drawContext.canvas.nativeCanvas.drawText(
                            formatTime(second * 1000L),
                            x + 5f,
                            17f,
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.rgb(127, 147, 163)
                                textSize = 22f
                                isAntiAlias = true
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTimeline() {
    ResoundCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 42.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.AudioFile, contentDescription = null, tint = TextLo)
            Text("Your mix starts here", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add a track, then add one or more audio clips.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextLo,
            )
        }
    }
}

@Composable
private fun TrackLane(
    track: Track,
    accent: Color,
    contentDp: androidx.compose.ui.unit.Dp,
    pxPerMs: Float,
    scrollState: androidx.compose.foundation.ScrollState,
    selectedClip: Long?,
    enabled: Boolean,
    onAddClip: () -> Unit,
    onToggleMute: () -> Unit,
    onSelectClip: (Long) -> Unit,
    onMove: (Long, Long) -> Unit,
    onTrimIn: (Long, Long) -> Unit,
    onTrimOut: (Long, Long) -> Unit,
) {
    val selected = track.clips.firstOrNull { it.id == selectedClip }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Panel,
        border = BorderStroke(1.dp, if (selected != null) Signal.copy(alpha = 0.55f) else Line),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.width(TRACK_LABEL_WIDTH - 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(34.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(accent),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            track.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (track.muted) TextLo else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TinyControl("+") { if (enabled) onAddClip() }
                        TinyControl("M", active = track.muted) { if (enabled) onToggleMute() }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(LANE_H.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Ink)
                        .horizontalScroll(scrollState),
                ) {
                    Canvas(modifier = Modifier.width(contentDp).height(LANE_H.dp)) {
                        val seconds = (size.width / (pxPerMs * 1000f)).toInt().coerceAtLeast(0)
                        for (second in 0..seconds) {
                            val x = second * 1000f * pxPerMs
                            drawLine(LineSoft, Offset(x, 0f), Offset(x, size.height), 1f)
                        }
                        drawLine(Line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
                    }
                    Box(modifier = Modifier.width(contentDp).height(LANE_H.dp)) {
                        track.clips.forEach { clip ->
                            ClipBox(
                                clip = clip,
                                pxPerMs = pxPerMs,
                                accent = accent,
                                muted = track.muted,
                                selected = selectedClip == clip.id,
                                onSelect = { onSelectClip(clip.id) },
                                onMove = { onMove(clip.id, it) },
                                onTrimIn = { onTrimIn(clip.id, it) },
                                onTrimOut = { onTrimOut(clip.id, it) },
                            )
                        }
                    }
                }
            }

            if (selected != null) {
                Spacer(Modifier.height(10.dp))
                SelectedClipReadout(selected)
            }
        }
    }
}

@Composable
private fun SelectedClipReadout(clip: Clip) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClipMetric("START", formatTime(clip.startMs))
        ClipMetric("DURATION", formatTime(clip.lengthMs))
        ClipMetric("END", formatTime(clip.startMs + clip.lengthMs))
    }
}

@Composable
private fun ClipMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextLo)
        Text(value, style = TimecodeStyle, color = Signal)
    }
}

@Composable
private fun TinyControl(label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (active) Amber else PanelHi,
        contentColor = if (active) Ink else TextMid,
        border = BorderStroke(1.dp, if (active) Amber else Line),
        modifier = Modifier.width(34.dp).height(30.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
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
            .height(LANE_H.dp)
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(9.dp))
            .pointerInput(clip.id) {
                val edge = 30.dp.toPx()
                detectDragGestures(
                    onDragStart = { position ->
                        selectCallback.value()
                        mode = when {
                            position.x < edge -> 1
                            position.x > size.width - edge -> 2
                            else -> 0
                        }
                    },
                ) { change, drag ->
                    change.consume()
                    val currentScale = scale.value
                    if (currentScale > 0f) {
                        val delta = (drag.x / currentScale).toLong()
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
            val border = if (selected) Signal else accent.copy(alpha = 0.55f)
            drawRoundRect(
                color = border,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
                style = Stroke(width = if (selected) 4f else 2f),
            )
            if (selected) {
                val handleWidth = 12.dp.toPx()
                drawRoundRect(
                    color = Signal,
                    topLeft = Offset(0f, 0f),
                    size = Size(handleWidth, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                )
                drawRoundRect(
                    color = Signal,
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
            clip.source.displayName,
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
