package com.typezero.resound.feature.edit

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.typezero.resound.core.audio.AudioFile
import com.typezero.resound.core.audio.Waveform
import com.typezero.resound.core.audio.WaveformProvenance
import com.typezero.resound.core.io.NamingRequest
import com.typezero.resound.feature.record.RecordingRecoveryAdmission
import com.typezero.resound.ui.components.NamingPanel
import com.typezero.resound.ui.components.NamingPreview
import com.typezero.resound.ui.components.ResoundBottomBar
import com.typezero.resound.ui.components.ResoundDestination
import com.typezero.resound.ui.theme.ResoundTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/** Debug-only visual fixtures. No player, microphone, provider, recovery store, or exporter. */
object EditorPreviewEvents {
    private val events = mutableListOf<String>()
    @JvmStatic fun reset() = synchronized(events) { events.clear() }
    @JvmStatic fun snapshot(): List<String> = synchronized(events) { events.toList() }
    fun record(event: String) = synchronized(events) { events.add(event); Unit }
}

object EditorPreviewSettings {
    @JvmStatic var initialScroll: Int = 0
}

private val fixtureFile = AudioFile(
    uri = Uri.parse("content://resound.ui.fixture/audio"),
    displayName = "UI fixture — synthetic waveform.wav",
    durationMs = 167_880,
    sampleRate = 44_100,
    channels = 2,
    mimeType = "audio/wav",
)

private val fixtureWaveform: Waveform by lazy {
    val peaks = FloatArray(640) { index ->
        val t = index / 640f
        val envelope = 0.18f + 0.65f * abs(sin(t * 19f))
        (envelope * (0.28f + 0.72f * abs(sin(index * 2.39996f)))).coerceIn(0f, 1f)
    }
    Waveform(FloatArray(peaks.size) { -peaks[it] }, peaks, fixtureFile.durationMs,
        WaveformProvenance.SYNTHETIC)
}

private val fixtureCallbacks = EditorWorkspaceCallbacks(
    onOpen = { EditorPreviewEvents.record("open") },
    onRecord = { EditorPreviewEvents.record("record") },
    onClose = { EditorPreviewEvents.record("close") },
    onSelectionChange = { start, end -> EditorPreviewEvents.record("selection:$start:$end") },
    onSeekBack = { EditorPreviewEvents.record("seek-back") },
    onPlayPause = { EditorPreviewEvents.record("play-pause") },
    onSeekForward = { EditorPreviewEvents.record("seek-forward") },
    onTool = { EditorPreviewEvents.record("tool:${it.name}") },
    onRingtone = { EditorPreviewEvents.record("ringtone") },
)

private fun loadedFixture() = EditorWorkspaceUiState(
    file = fixtureFile,
    waveform = fixtureWaveform,
    selStart = 32_450,
    selEnd = 70_220,
    playhead = 45_120,
    status = "Visual fixture: synthetic waveform; no audio is played.",
    transportEnabled = true,
    toolsEnabled = true,
    closeEnabled = true,
)

@Composable
private fun Fixture(state: EditorWorkspaceUiState, recovery: Boolean = false) {
    ResoundTheme {
        Scaffold(
            bottomBar = {
                ResoundBottomBar(ResoundDestination.Editor) {
                    EditorPreviewEvents.record("destination:${it.name}")
                }
            },
        ) { contentPadding ->
            EditorWorkspace(
                state = state,
                callbacks = fixtureCallbacks,
                scrollState = rememberScrollState(EditorPreviewSettings.initialScroll),
                modifier = Modifier.padding(contentPadding),
                recordingOptions = {
                    NamingPanel("Recording name", "", enabled = !state.recording && !state.sourceBusy,
                        ext = "m4a", recording = true,
                        frozen = NamingRequest().takeIf { state.recording || state.sourceBusy }, inDialog = true,
                        onChange = { EditorPreviewEvents.record("recording-name:$it") })
                },
                recoveryContent = {
                    if (recovery) {
                        val candidate = RecordingRecoveryAdmission.Candidate(
                            File("ui-fixture-unsaved-recording.m4a"), 1, RecordingRecoveryAdmission())
                        DraftRecoveryCard(listOf(candidate), mapOf(candidate to RecoveryHealth.UNVERIFIED),
                            enabled = !state.busy && !state.recording && !state.sourceBusy,
                            onLoad = { EditorPreviewEvents.record("recovery-open") },
                            onSave = { EditorPreviewEvents.record("recovery-save") },
                            onDiscard = { EditorPreviewEvents.record("recovery-discard") })
                    }
                },
                exportOptions = {
                    NamingPanel("Export new copy name", "", enabled = !state.recording && !state.sourceBusy,
                        ext = "wav", trimOption = true, trimRange = state.selStart to state.selEnd, inDialog = true,
                        onChange = { EditorPreviewEvents.record("export-name:$it") })
                },
            )
        }
    }
}

@Preview(widthDp = 412, heightDp = 850)
@Composable fun EditorPreviewEmpty() = Fixture(EditorWorkspaceUiState())

@Preview(widthDp = 412, heightDp = 850)
@Composable fun EditorPreviewLoaded() = Fixture(loadedFixture())

@Preview(widthDp = 412, heightDp = 850)
@Composable fun EditorPreviewRecording() = Fixture(EditorWorkspaceUiState(
    recording = true, recordElapsedMs = 12_000, status = "Recording… (visual fixture)",
))

@Preview(widthDp = 412, heightDp = 850)
@Composable fun EditorPreviewUnavailable() = Fixture(loadedFixture().copy(
    waveform = Waveform.unavailable(fixtureFile.durationMs), transportEnabled = false,
    status = "Playback unavailable (visual fixture)",
))

@Preview(widthDp = 412, heightDp = 850)
@Composable fun EditorPreviewRecovery() = Fixture(EditorWorkspaceUiState(), recovery = true)

@Preview(widthDp = 412, heightDp = 850)
@Composable fun EditorPreviewProcessing() = Fixture(loadedFixture().copy(
    busy = true, sourceBusy = true, toolsEnabled = false, transportEnabled = false,
    closeEnabled = false, status = "Processing… (visual fixture)",
))

@Composable
private fun OperationFixture(op: EditOp) {
    ResoundTheme {
        Fixture(loadedFixture())
        OpDialog(op,
            onDismiss = { EditorPreviewEvents.record("operation-dismiss") },
            convertNamingPreview = { ext -> NamingPreview("", ext) },
            onConfirm = { params -> EditorPreviewEvents.record("operation:${op.name}:$params") },
        )
    }
}

@Preview(widthDp = 412, heightDp = 850)
@Composable fun EditorPreviewConvert() = OperationFixture(EditOp.CONVERT)

@Preview(widthDp = 412, heightDp = 850)
@Composable fun EditorPreviewEq() = OperationFixture(EditOp.EQ)
