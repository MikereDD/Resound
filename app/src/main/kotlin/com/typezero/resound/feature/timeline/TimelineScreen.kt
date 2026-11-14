/*
 * file:    TimelineScreen.kt
 * author:  Typezer∅
 * version: 0.8.0-dev.4
 * desc:    Premium multitrack workspace with a shared ruler, compact track
 *          controls, zoomable lanes, draggable/trim-capable clips, and a
 *          prominent mixdown action.
 */
package com.typezero.resound.feature.timeline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.typezero.resound.core.audio.AudioFiles
import com.typezero.resound.core.audio.WaveformExtractor
import com.typezero.resound.core.ffmpeg.FFmpegRunner
import com.typezero.resound.core.io.Outputs
import com.typezero.resound.core.io.NamingPreferences
import com.typezero.resound.core.io.NamingRequest
import com.typezero.resound.ui.components.NamingPanel
import com.typezero.resound.ui.theme.Accent
import com.typezero.resound.ui.theme.PanelHi
import com.typezero.resound.ui.theme.TextHi
import com.typezero.resound.feature.effects.Effects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.typezero.resound.core.io.OperationFiles
import com.typezero.resound.core.io.runCommitted
import com.typezero.resound.core.io.allocateOwnedTemp

@Composable
fun TimelineScreen(
    waveformExtractor: WaveformExtractor,
    ffmpeg: FFmpegRunner,
    session: TimelineSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val namingPreferences = remember(context) { NamingPreferences.forContext(context) }
    val namingPatterns by namingPreferences.state.collectAsState()
    val namingPreferenceNotice by namingPreferences.notice.collectAsState()
    LaunchedEffect(namingPreferences) { namingPreferences.initialize() }

    androidx.activity.compose.BackHandler { onBack() }

    // Arrangement state lives in [session] (owned above the destination switch).
    var tracks by session::tracks
    var selectedClip by session::selectedClip
    var addTrackId by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Add a track, then place audio clips on the timeline.") }
    // Export Mix outcomes are also announced at the bottom of the viewport: the toolbar can sit far below the
    // header status line on long or many-track arrangements (U1). Latest-only: a new outcome cancels the visible
    // or queued previous one.
    val mixFeedback = remember(scope) { LatestOnlySnackbar(scope) }
    fun announceMixOutcome(message: String) {
        status = message
        mixFeedback.announce(message)
    }
    var pxPerSec by session::pxPerSec

    val editingEnabled = session.recoveryReady && !busy

    fun nextId(): Long = session.nextId()
    fun updateTrack(id: Long, transform: (Track) -> Track) {
        if (!session.recoveryReady) return
        tracks = tracks.map { if (it.id == id) transform(it) else it }
    }
    fun updateClip(trackId: Long, clipId: Long, transform: (Clip) -> Clip) {
        if (!editingEnabled) return
        updateTrack(trackId) { track ->
            track.copy(clips = track.clips.map { if (it.id == clipId) transform(it) else it })
        }
    }

    val horizontalScroll = rememberTimelineScrollState()

    val clipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val trackId = addTrackId
        addTrackId = null
        if (uri == null || trackId == null || !session.recoveryReady) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                busy = true
                status = "Adding clip…"
                val audio = AudioFiles.readMetadata(context, uri)
                if (audio.durationMs <= 0L) {
                    status = "Could not add clip: unknown or zero duration."
                    return@launch
                }
                // Metadata-only recovery needs a grant that survives process death; reject imports
                // whose provider will not retain read access rather than promising durable recovery.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val waveform = waveformExtractor.extract(audio, targetBuckets = 600)
                updateTrack(trackId) { it.copy(clips = it.clips + Clip(nextId(), audio, waveform, startMs = 0L)) }
                status = "Added ${audio.displayName}"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                status = "Could not add clip: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    fun exportMix() {
        if (!editingEnabled) return
        val exportNaming = NamingRequest(namingPatterns.exports)
        scope.launch {
            val owned = OperationFiles()
            try {
                busy = true
                status = "Mixing tracks…"
                val active = tracks.flatMap { track ->
                    if (track.muted) emptyList() else track.clips.map { track to it }
                }
                if (active.isEmpty()) {
                    announceMixOutcome("Add a clip to an unmuted track first.")
                    return@launch
                }
                if (active.any { !it.second.sourceAvailable }) {
                    announceMixOutcome("Mix blocked: an audio reference is unavailable. Automatic draft retained.")
                    return@launch
                }
                val inputs = active.map { (track, clip) ->
                    Effects.TimelineInput(
                        path = AudioFiles.resolveToCache(context, clip.source, owned).absolutePath,
                        startMs = clip.startMs,
                        inMs = clip.sourceInMs,
                        outMs = clip.sourceOutMs,
                        volume = track.volume,
                    )
                }
                val temp = allocateOwnedTemp(context, "mix", "m4a", owned)
                val result = ffmpeg.run(Effects.mixTimeline(inputs, temp.absolutePath), 0L, owned)
                announceMixOutcome(if (result.isSuccess) {
                    val published = runCommitted { Outputs.publishNamedToMusic(context, temp, "mix", "m4a", exportNaming) }
                    "Mix saved to ${published.savedDescription}"
                } else {
                    "Mix failed (rc=${result.returnCode})"
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                announceMixOutcome("Mix failed: ${t.message}")
            } finally {
                owned.deleteAll()
                busy = false
            }
        }
    }

    Box(modifier) {
    MultitrackWorkspace(
        state = MultitrackUiState(
            tracks = tracks,
            selectedClip = selectedClip,
            pxPerSec = pxPerSec,
            status = status,
            busy = busy,
            recoveryReady = session.recoveryReady,
            draftStatus = session.draftStatus,
            persistenceError = session.persistenceError,
            namingNotice = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) namingPreferenceNotice else null,
            namingAvailable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q,
        ),
        callbacks = MultitrackCallbacks(
            onAddTrack = { tracks = tracks + Track(nextId(), "Track ${tracks.size + 1}") },
            onExportMix = { exportMix() },
            onZoomOut = { if (editingEnabled) pxPerSec = zoomedOut(pxPerSec) },
            onZoomIn = { if (editingEnabled) pxPerSec = zoomedIn(pxPerSec) },
            onAddClip = { trackId ->
                addTrackId = trackId
                clipPicker.launch(arrayOf("audio/*", "video/*"))
            },
            onToggleMute = { trackId -> updateTrack(trackId) { it.copy(muted = !it.muted) } },
            onSelectClip = { if (editingEnabled) selectedClip = it },
            onMove = { trackId, clipId, delta ->
                updateClip(trackId, clipId) { it.copy(startMs = (it.startMs + delta).coerceAtLeast(0L)) }
            },
            onTrimIn = { trackId, clipId, delta ->
                updateClip(trackId, clipId) { clip ->
                    val r = TrimMath.trimIn(ClipSpan(clip.startMs, clip.sourceInMs, clip.sourceOutMs), delta, clip.source.durationMs)
                    clip.copy(startMs = r.startMs, sourceInMs = r.inMs, sourceOutMs = r.outMs)
                }
            },
            onTrimOut = { trackId, clipId, delta ->
                updateClip(trackId, clipId) { clip ->
                    val r = TrimMath.trimOut(ClipSpan(clip.startMs, clip.sourceInMs, clip.sourceOutMs), delta, clip.source.durationMs)
                    clip.copy(sourceOutMs = r.outMs)
                }
            },
        ),
        modifier = Modifier.fillMaxSize(),
        horizontalScroll = horizontalScroll,
        exportNaming = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                NamingPanel("Export new copy name", namingPatterns.exports,
                    enabled = editingEnabled, ext = "m4a", inDialog = true,
                    onChange = { namingPreferences.update(exports = it) })
            }
        },
        diagnostics = { TimelineDiagnostics(session) },
    )
    SnackbarHost(mixFeedback.hostState, Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 8.dp)) { data ->
        MixFeedbackSnackbar(data)
    }
    }
}

/** Export Mix outcome in the workstation palette (no light Material inverse surface). */
@Composable
private fun MixFeedbackSnackbar(data: SnackbarData) {
    Snackbar(
        snackbarData = data,
        shape = RoundedCornerShape(10.dp),
        containerColor = PanelHi,
        contentColor = TextHi,
        actionColor = Accent,
        dismissActionContentColor = Accent,
    )
}
