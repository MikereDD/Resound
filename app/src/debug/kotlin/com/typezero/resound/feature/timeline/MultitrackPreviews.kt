package com.typezero.resound.feature.timeline

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.typezero.resound.core.audio.AudioFile
import com.typezero.resound.core.audio.Waveform
import com.typezero.resound.core.audio.WaveformProvenance
import com.typezero.resound.feature.edit.EditorPreviewEvents
import com.typezero.resound.feature.edit.EditorPreviewSettings
import com.typezero.resound.ui.components.NamingPanel
import com.typezero.resound.ui.components.ResoundBottomBar
import com.typezero.resound.ui.components.ResoundDestination
import com.typezero.resound.ui.theme.ResoundTheme
import kotlin.math.abs
import kotlin.math.sin

/** Debug-only visual fixtures: synthetic waveforms; no picker, session store, decoder or mixer. */
private fun fixtureAudio(name: String, durationMs: Long) = AudioFile(
    uri = Uri.parse("content://resound.ui.fixture/${name.lowercase()}"),
    displayName = "$name (synthetic).wav",
    durationMs = durationMs,
    sampleRate = 44_100,
    channels = 2,
    mimeType = "audio/wav",
)

private fun fixtureWave(seed: Float, durationMs: Long): Waveform {
    val n = 400
    val peaks = FloatArray(n) { i ->
        val t = i / n.toFloat()
        ((0.2f + 0.6f * abs(sin(t * (7f + seed)))) * (0.3f + 0.7f * abs(sin(i * (2.399f + seed / 10f))))).coerceIn(0f, 1f)
    }
    return Waveform(FloatArray(n) { -peaks[it] }, peaks, durationMs, WaveformProvenance.SYNTHETIC)
}

private fun clip(id: Long, name: String, durationMs: Long, startMs: Long, seed: Float, available: Boolean = true) =
    Clip(id, fixtureAudio(name, durationMs), fixtureWave(seed, durationMs), startMs = startMs, sourceAvailable = available)

private fun populated(): List<Track> = listOf(
    Track(1, "Vocals", listOf(clip(11, "Vocals", 28_000, 0, 1f))),
    Track(2, "Drums", listOf(clip(21, "Drums", 24_000, 1_500, 4f))),
    Track(3, "Bass", listOf(clip(31, "Bass", 20_000, 4_000, 2f))),
    Track(4, "Guitar", listOf(clip(41, "Guitar L", 12_000, 0, 3f), clip(42, "Guitar R", 12_000, 14_000, 5f)), muted = true),
    Track(5, "FX", listOf(clip(51, "FX", 6_000, 9_000, 6f))),
    Track(6, "Track 6"),
)

private const val DRAFT = "Automatic recovery draft — not an explicit project save"
private const val FIXTURE_STATUS = "Visual fixture: synthetic waveforms; no audio is played."

private val fixtureCallbacks = MultitrackCallbacks(
    onAddTrack = { EditorPreviewEvents.record("add-track") },
    onExportMix = { EditorPreviewEvents.record("export-mix") },
    onZoomOut = { EditorPreviewEvents.record("zoom-out") },
    onZoomIn = { EditorPreviewEvents.record("zoom-in") },
    onAddClip = { EditorPreviewEvents.record("add-clip:$it") },
    onToggleMute = { EditorPreviewEvents.record("mute:$it") },
    onSelectClip = { EditorPreviewEvents.record("select:$it") },
    onMove = { _, clip, delta -> EditorPreviewEvents.record("move:$clip:$delta") },
    onTrimIn = { _, clip, delta -> EditorPreviewEvents.record("trim-in:$clip:$delta") },
    onTrimOut = { _, clip, delta -> EditorPreviewEvents.record("trim-out:$clip:$delta") },
)

@Composable
private fun Fixture(state: MultitrackUiState) {
    ResoundTheme {
        Scaffold(bottomBar = {
            ResoundBottomBar(ResoundDestination.Multitrack) { EditorPreviewEvents.record("destination:${it.name}") }
        }) { padding ->
            MultitrackWorkspace(
                state = state,
                callbacks = fixtureCallbacks,
                modifier = Modifier.padding(padding),
                scrollState = rememberScrollState(EditorPreviewSettings.initialScroll),
                exportNaming = {
                    NamingPanel("Export new copy name", "", enabled = true, ext = "m4a", inDialog = true,
                        onChange = { EditorPreviewEvents.record("export-name:$it") })
                },
            )
        }
    }
}

@Composable
private fun NavOnly(selected: ResoundDestination) {
    ResoundTheme {
        Scaffold(bottomBar = { ResoundBottomBar(selected) { EditorPreviewEvents.record("destination:${it.name}") } }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Preview(widthDp = 448, heightDp = 921)
@Composable fun MultitrackPreviewEmpty() = Fixture(MultitrackUiState(draftStatus = DRAFT))

@Preview(widthDp = 448, heightDp = 921)
@Composable fun MultitrackPreviewOneTrack() = Fixture(MultitrackUiState(
    tracks = listOf(Track(1, "Vocals", listOf(clip(11, "Vocals", 28_000, 0, 1f)))),
    status = "Added Vocals (synthetic).wav", draftStatus = DRAFT,
))

@Preview(widthDp = 448, heightDp = 921)
@Composable fun MultitrackPreviewPopulated() = Fixture(MultitrackUiState(tracks = populated(), status = FIXTURE_STATUS, draftStatus = DRAFT))

@Preview(widthDp = 448, heightDp = 921)
@Composable fun MultitrackPreviewSelected() = Fixture(MultitrackUiState(
    tracks = populated(), selectedClip = 21, status = FIXTURE_STATUS, draftStatus = DRAFT,
))

@Preview(widthDp = 448, heightDp = 921)
@Composable fun MultitrackPreviewZoomed() = Fixture(MultitrackUiState(
    tracks = populated(), selectedClip = 11, pxPerSec = 120f, status = FIXTURE_STATUS, draftStatus = DRAFT,
))

@Preview(widthDp = 448, heightDp = 921)
@Composable fun MultitrackPreviewRecovery() = Fixture(MultitrackUiState(
    tracks = listOf(
        Track(1, "Vocals", listOf(clip(11, "Vocals", 28_000, 0, 1f))),
        Track(2, "Drums", listOf(clip(21, "Drums", 24_000, 1_500, 4f, available = false))),
    ),
    recoveryReady = false,
    status = "Restoring automatic draft… (visual fixture)",
    draftStatus = "Recovered automatic draft; 1 audio reference unavailable (visual fixture)",
))

@Preview(widthDp = 412, heightDp = 850) @Composable fun MultitrackPreviewNavEditor() = NavOnly(ResoundDestination.Editor)
@Preview(widthDp = 412, heightDp = 850) @Composable fun MultitrackPreviewNavMultitrack() = NavOnly(ResoundDestination.Multitrack)
@Preview(widthDp = 412, heightDp = 850) @Composable fun MultitrackPreviewNavLibrary() = NavOnly(ResoundDestination.Library)
@Preview(widthDp = 412, heightDp = 850) @Composable fun MultitrackPreviewNavSettings() = NavOnly(ResoundDestination.Settings)
