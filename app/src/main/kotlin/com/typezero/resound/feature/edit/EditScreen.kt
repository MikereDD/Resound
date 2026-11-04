/*
 * file:    EditScreen.kt
 * author:  Mike Redd (typezero)
 * version: 0.8.0-dev.5
 * desc:    Premium editor workspace matching the approved Resound visual direction:
 *          file card, waveform stage, timecode/transport strip, tool tiles, status,
 *          and output destination. Audio behavior remains unchanged.
 */
package com.typezero.resound.feature.edit

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.AudioFile as AudioFileIcon
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.typezero.resound.core.audio.AudioFile
import com.typezero.resound.core.audio.AudioFiles
import com.typezero.resound.core.audio.Waveform
import com.typezero.resound.core.audio.WaveformProvenance
import com.typezero.resound.core.audio.WaveformExtractor
import com.typezero.resound.core.ffmpeg.FFmpegRunner
import com.typezero.resound.core.io.Outputs
import com.typezero.resound.core.io.Ringtones
import com.typezero.resound.feature.effects.Effects
import com.typezero.resound.feature.record.RecordingController
import com.typezero.resound.feature.record.RecordingRecoveryAdmission
import kotlinx.coroutines.flow.collect
import com.typezero.resound.core.io.PublishedButUntracked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.typezero.resound.ui.components.ResoundCard
import com.typezero.resound.ui.theme.Amber
import com.typezero.resound.ui.theme.InkRaised
import com.typezero.resound.ui.theme.Line
import com.typezero.resound.ui.theme.PanelHi
import com.typezero.resound.ui.theme.Signal
import com.typezero.resound.ui.theme.SignalGlow
import com.typezero.resound.ui.theme.TextHi
import com.typezero.resound.ui.theme.TextLo
import com.typezero.resound.ui.theme.TextMid
import com.typezero.resound.ui.theme.TimecodeStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import com.typezero.resound.core.io.DraftStore
import com.typezero.resound.core.io.OperationFiles
import com.typezero.resound.core.io.publishDraft
import com.typezero.resound.core.io.publishDraftIfUnpublished
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.typezero.resound.core.io.allocateOwnedTemp
import com.typezero.resound.core.io.runCommitted

private fun fmt(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

@Composable
fun EditScreen(
    waveformExtractor: WaveformExtractor,
    ffmpeg: FFmpegRunner,
    recorder: RecordingController,
    initialUri: Uri? = null,
    modifier: Modifier = Modifier,
    playerFactory: com.typezero.resound.core.audio.AudioPlayerFactory =
        com.typezero.resound.core.audio.PlayerRuntimeProvider.playerFactory(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drafts = remember(context, recorder) { DraftStore.forContext(context, recorder.recoveryAdmission) }

    // API 26-28 needs WRITE_EXTERNAL_STORAGE to publish into public Music.
    // 29+ uses MediaStore and needs nothing — this is a no-op there.
    val writePerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            writePerm.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    var current by remember { mutableStateOf<AudioFile?>(null) }
    var waveform by remember { mutableStateOf<Waveform?>(null) }
    var selStart by remember { mutableLongStateOf(0L) }
    var selEnd by remember { mutableLongStateOf(0L) }
    var playhead by remember { mutableLongStateOf(0L) }
    var status by remember { mutableStateOf("Open a file or record to begin.") }
    var busy by remember { mutableStateOf(false) }
    var pendingOp by remember { mutableStateOf<EditOp?>(null) }
    var pendingMulti by remember { mutableStateOf<EditOp?>(null) }

    val player = remember { playerFactory.create() }
    var playing by remember { mutableStateOf(false) }
    var playbackReady by remember { mutableStateOf(false) }

    // While playing, advance the playhead and stop at the end of the selection.
    LaunchedEffect(player) {
        while (true) {
            delay(50)
            try {
                if (playbackReady && current?.uri?.let { player.readyFor(it) } != true) {
                    playbackReady = false
                    playing = false
                    status = "Playback unavailable"
                }
                if (playing) {
                    playhead = player.currentMs
                    if (!player.isPlaying || playhead >= selEnd) {
                        player.pause()
                        playing = false
                    }
                }
            } catch (c: CancellationException) { throw c } catch (_: Exception) {
                playbackReady = false
                playing = false
                status = "Playback unavailable"
            }
        }
    }

    var recording by remember { mutableStateOf(false) }
    var recorderBusy by remember { mutableStateOf(false) } // start/stop in flight
    var recorderStarting by remember { mutableStateOf(false) } // native start in flight
    var stopWhenStarted by remember { mutableStateOf(false) } // Editor left the foreground during start
    var draftList by remember { mutableStateOf(emptyList<RecordingRecoveryAdmission.Candidate>()) }
    var recoveryNotice by remember { mutableStateOf<String?>(null) }
    var recordToken by remember { mutableLongStateOf(0L) }
    var recordElapsed by remember { mutableLongStateOf(0L) } // seconds
    var recordFile by remember { mutableStateOf<File?>(null) }

    // Publication receipts never authorize lifecycle deletion of a recording draft.
    fun refreshDrafts() {
        drafts.requestRecoveryRefresh()
    }
    // Also runs after a process restart: unpublished drafts are rediscovered from filesDir.
    LaunchedEffect(drafts) {
        drafts.recoveryUpdates().collect { listing ->
            draftList = listing.candidates
            recoveryNotice = listing.protections.map { protection ->
                when (protection.state) {
                    RecordingRecoveryAdmission.State.STARTING -> "Recording draft protected: native start in progress."
                    RecordingRecoveryAdmission.State.CAPTURING -> "Recording draft protected: capture in progress."
                    RecordingRecoveryAdmission.State.STOPPING -> "Recording draft protected: waiting for recorder termination."
                    RecordingRecoveryAdmission.State.INDETERMINATE -> "Recording draft retained and protected: native termination is uncertain."
                    RecordingRecoveryAdmission.State.FINALIZED -> "Recording draft protected: recovery work or its termination is uncertain."
                }
            }.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            // Stop the mic in the application scope (this composition scope is being cancelled).
            // The captured file stays on disk as a draft; nothing is deleted here on Main.
            // Only this composition's session may be stopped. A cancelled start owns its
            // unpublished ticket in the controller and schedules ticket-specific cleanup there.
            val token = recordToken
            if (token != 0L) recorder.stopDetached(token)
            // Keep the original even if it was published; Discard is an explicit user action.
        }
    }

    LaunchedEffect(recording) {
        while (recording) {
            delay(1000)
            recordElapsed += 1
        }
    }

    fun srcExt(): String =
        current?.displayName?.substringAfterLast('.', "m4a")?.ifEmpty { "m4a" } ?: "m4a"

    suspend fun loadPreview(
        af: AudioFile,
        onNativePreparationAttempted: () -> Unit = {},
        onPreparationSucceeded: () -> Unit = {},
    ): PreviewLoad.Result? {
        val result = PreviewLoad().load(af.durationMs, {
            player.invalidate()
            playbackReady = false
            playing = false
            // Keep A's identity while owned IO retirement runs. Cancellation/timeout
            // cannot abandon cleanup or adopt B beneath an old playing backend.
            player.awaitRetirement()
            waveform = null
            playhead = 0L
            current = af
            selStart = 0L
            selEnd = af.durationMs
        }, { waveformExtractor.extract(af, targetBuckets = 1200) }, {
            onNativePreparationAttempted()
            player.prepare(context, af.uri)
            onPreparationSucceeded()
        }, isReady = { player.readyFor(af.uri) }, onWaveform = { waveform = it })
        if (result != null) {
            waveform = result.waveform
            playbackReady = result.ready && player.readyFor(af.uri)
        }
        return if (result != null && result.ready && !playbackReady) {
            result.copy(ready = false, message = "Playback unavailable")
        } else result
    }

    /** Await actual metadata/decode/prepare work; recovery must not lease only a launch. */
    suspend fun loadIntoAwaited(
        uri: Uri,
        onNativePreparationAttempted: () -> Unit = {},
        doneMsg: (AudioFile) -> String,
    ): Boolean {
        var preparationSucceeded = false
        try {
            status = "Loading…"
            val af = AudioFiles.readMetadata(context, uri)
            val result = loadPreview(af, onNativePreparationAttempted, { preparationSucceeded = true })
            if (result == null) {
                status = EMPTY_AUDIO_MESSAGE
                return false
            }
            val priorRecording = recordFile
            if (priorRecording != null && uri != Uri.fromFile(priorRecording)) recordFile = null
            status = result.message ?: doneMsg(af)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            status = "Load failed: ${t.message}"
        }
        // Preparation success is recorded at the native boundary, not inferred from UI text/readiness.
        return preparationSucceeded
    }

    fun loadInto(uri: Uri, doneMsg: (AudioFile) -> String) {
        if (busy || recorderBusy) return
        busy = true
        scope.launch {
            try { loadIntoAwaited(uri, doneMsg = doneMsg) } finally { busy = false }
        }
    }

    suspend fun loadDraftPreview(
        file: File,
        lease: RecordingRecoveryAdmission.Lease,
        doneMsg: (AudioFile) -> String,
    ) {
        // Same structured producer/retirement policy as the host regression. Installed reader
        // lifetime remains RP03; a completed retirement queue is not positive native release proof.
        lease.withPreviewPreparation(
            awaitRetirement = { player.awaitRetirement() },
            onUncertain = { status = "Draft retained and protected: playback cleanup is uncertain." },
        ) { nativeAttempt ->
            loadIntoAwaited(Uri.fromFile(file), nativeAttempt, doneMsg)
        }
    }

    fun openDraft(candidate: RecordingRecoveryAdmission.Candidate) {
        if (busy || recorderBusy) return
        busy = true
        scope.launch {
            try {
                drafts.withRecovery(candidate) { file, lease ->
                    loadDraftPreview(file, lease) { af -> "Loaded draft ${af.displayName}" }
                }
            } catch (c: CancellationException) { throw c }
            catch (t: Exception) { status = "Draft unavailable: ${t.message}" }
            finally { busy = false }
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { uri ->
            loadInto(uri) { af ->
                "Loaded ${af.displayName} · ${fmt(af.durationMs)} · ${af.sampleRate} Hz"
            }
        }
    }

    // Shared single-file run path: resolve source -> build args -> FFmpeg -> save.
    fun runSingle(
        label: String,
        outExt: String,
        totalMs: Long = current?.durationMs ?: 0L,
        makeArgs: (src: String, out: String) -> List<String>,
    ) {
        val af = current ?: return
        scope.launch {
            val owned = OperationFiles()
            try {
                busy = true
                status = "$label…"
                val src = AudioFiles.resolveToCache(context, af, owned)
                val temp = allocateOwnedTemp(context, label.lowercase().replace(" ", "-"), outExt, owned)
                val args = makeArgs(src.absolutePath, temp.absolutePath)
                val res = ffmpeg.run(args, totalMs, owned) { ratio, _ ->
                    if (ratio != null) status = "$label… ${(ratio * 100).toInt()}%"
                }
                status = if (res.isSuccess) {
                    val pub = runCommitted { Outputs.publishToMusic(context, temp, Outputs.exportName(label, outExt), outExt) }
                    "Saved to ${pub.displayPath}"
                } else {
                    "FFmpeg failed (rc=${res.returnCode})"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                status = "$label failed: ${t.message}"
            } finally {
                owned.deleteAll()
                busy = false
            }
        }
    }

    // Two-file run path for Mix / Concat.
    fun runDual(
        label: String,
        outExt: String,
        secondUri: Uri,
        makeArgs: (a: String, b: String, out: String) -> List<String>,
    ) {
        val af = current ?: return
        scope.launch {
            val owned = OperationFiles()
            try {
                busy = true
                status = "$label…"
                val a = AudioFiles.resolveToCache(context, af, owned)
                val secondAf = AudioFiles.readMetadata(context, secondUri)
                val b = AudioFiles.resolveToCache(context, secondAf, owned)
                val temp = allocateOwnedTemp(context, label.lowercase(), outExt, owned)
                val args = makeArgs(a.absolutePath, b.absolutePath, temp.absolutePath)
                val res = ffmpeg.run(args, 0L, owned)
                status = if (res.isSuccess) {
                    val pub = runCommitted { Outputs.publishToMusic(context, temp, Outputs.exportName(label, outExt), outExt) }
                    "Saved to ${pub.displayPath}"
                } else {
                    "FFmpeg failed (rc=${res.returnCode})"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                status = "$label failed: ${t.message}"
            } finally {
                owned.deleteAll()
                busy = false
            }
        }
    }

    fun onParams(op: EditOp, p: OpParams) {
        val af = current ?: return
        when (op) {
            EditOp.VOLUME -> runSingle("Volume", srcExt()) { s, o ->
                Effects.volume(s, o, (p as OpParams.Volume).factor)
            }
            EditOp.SPEED -> runSingle("Speed", srcExt()) { s, o ->
                Effects.speed(s, o, (p as OpParams.Speed).factor)
            }
            EditOp.PITCH -> runSingle("Pitch", srcExt()) { s, o ->
                Effects.pitchSemitones(s, o, (p as OpParams.Pitch).semitones, af.sampleRate)
            }
            EditOp.FADE -> runSingle("Fade", srcExt()) { s, o ->
                Effects.fade(s, o, af.durationMs, (p as OpParams.Fade).fadeMs)
            }
            EditOp.EQ -> runSingle("EQ", srcExt()) { s, o ->
                val e = p as OpParams.Eq; Effects.equalizerBand(s, o, e.freq, e.gainDb)
            }
            EditOp.CONVERT -> {
                val e = p as OpParams.Convert
                runSingle("Convert", e.ext) { s, o -> Effects.convert(s, o) }
            }
            EditOp.COMPRESS -> {
                val e = p as OpParams.Compress
                runSingle("Compress", srcExt()) { s, o ->
                    Effects.compress(s, o, e.bitrateKbps, af.sampleRate, af.channels)
                }
            }
            else -> {}
        }
    }

    fun stopRecording() {
        if (recorderBusy || !recording) return
        val token = recordToken
        if (token == 0L) return
        recorderBusy = true
        scope.launch {
            try {
                val path = try { recorder.stop(token) } catch (c: CancellationException) { throw c } catch (t: Exception) { null }
                recordToken = 0L
                recording = false
                if (path == null) {
                    status = recorder.failure ?: "Recording not finalized. Draft retained."
                    refreshDrafts()
                    return@launch
                }
                val recFile = File(path)
                recordFile = recFile
                busy = true
                val candidate = withContext(Dispatchers.IO) { drafts.candidate(recFile, token) }
                    ?: error("Recording draft unavailable; retained without publication")
                drafts.withRecovery(candidate) { admittedFile, lease ->
                    status = try {
                        val pub = publishDraft(context, drafts, admittedFile)
                        "Recorded & saved to ${pub.displayPath}"
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: PublishedButUntracked) {
                        "Audio was saved to the library, but local tracking failed. Draft kept; re-saving may duplicate it."
                    } catch (t: Throwable) {
                        "Recorded, but save to library failed (${t.message}). Kept as draft: ${admittedFile.name}"
                    }
                    val publicationStatus = status
                    loadDraftPreview(admittedFile, lease) { publicationStatus }
                }
                refreshDrafts()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                status = "Recording kept as draft, but loading failed: ${t.message}"
            } finally {
                busy = false
                recorderBusy = false
            }
        }
    }

    /** Stop for leaving the foreground; never resets `busy` owned by another operation. */
    fun stopForBackground() {
        if (!recording || recorderBusy) return
        if (!busy) { stopRecording(); return }
        val token = recordToken
        if (token == 0L) return
        recordToken = 0L
        recording = false
        recorder.stopDetached(token) // finalized draft stays in the recovery list
        status = "Recording stopped when Resound left the screen. Draft kept for recovery."
    }

    fun startRecording() {
        if (recorderBusy || recording) return
        recorderBusy = true
        recorderStarting = true
        stopWhenStarted = false
        scope.launch {
            try {
                val temp = withContext(Dispatchers.IO) { drafts.newFile("recording", "m4a") }
                recordToken = recorder.start(temp.absolutePath)
                recordElapsed = 0L
                recording = true
                status = "Recording…"
            } catch (c: CancellationException) {
                // Controller owns ticket-specific cancelled-start cleanup, including token zero.
                throw c
            } catch (t: Throwable) {
                // Retain even failed/partial start bytes; uncertainty is guarded by the owner.
                recording = false
                status = "Record failed; draft retained: ${t.message}"
            } finally {
                recorderStarting = false
                recorderBusy = false
            }
            if (stopWhenStarted) {
                stopWhenStarted = false
                stopForBackground()
            }
        }
    }

    fun publishExistingDraft(candidate: RecordingRecoveryAdmission.Candidate) {
        if (busy || recorderBusy) return
        busy = true
        scope.launch {
            try {
                status = try {
                    drafts.withRecovery(candidate) { file, _ ->
                        publishDraftIfUnpublished(context, drafts, file)?.let { "Saved to ${it.displayPath}" }
                            ?: "Already saved to the library. Draft kept."
                    }
                } catch (c: CancellationException) { throw c
                } catch (e: PublishedButUntracked) {
                    "Audio was saved, but local tracking failed. Draft kept; saving again may duplicate it."
                } catch (t: Throwable) { "Save unavailable (${t.message}). Draft kept." }
                refreshDrafts()
            } finally { busy = false }
        }
    }

    fun discardDraft(candidate: RecordingRecoveryAdmission.Candidate) {
        if (busy || recorderBusy) return
        busy = true
        scope.launch {
            try {
                val ok = drafts.withRecovery(candidate) { file, lease ->
                    if (current?.uri == Uri.fromFile(file)) {
                        // Never unlink a loaded/playing source under its reader: revoke playback,
                        // await owned retirement (throws if cleanup failed, keeping the draft),
                        // then clear the Editor so it no longer shows a deleted file.
                        playing = false
                        playbackReady = false
                        player.invalidate()
                        try {
                            player.awaitRetirement()
                        } catch (failed: IllegalStateException) {
                            lease.retainIndeterminate("Discard: playback cleanup uncertain: ${failed.message}")
                            throw failed
                        }
                        current = null
                        waveform = null
                        selStart = 0L
                        selEnd = 0L
                        playhead = 0L
                        if (recordFile == file) recordFile = null
                    }
                    withContext(Dispatchers.IO) { file.delete() }
                }
                status = if (ok) "Draft discarded." else "Could not discard draft."
                refreshDrafts()
            } catch (c: CancellationException) { throw c }
            catch (t: Exception) { status = "Discard unavailable: ${t.message}" }
            finally { busy = false }
        }
    }

    val recordPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else status = "Microphone permission denied."
    }

    fun onRecordTap() {
        if (recording) {
            stopRecording()
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) startRecording() else recordPerm.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    fun transport(action: () -> Unit) {
        val uri = current?.uri ?: return
        if (!playbackReady || !player.readyFor(uri) || busy || recording) return
        try { action() } catch (c: CancellationException) { throw c } catch (_: Exception) {
            playbackReady = false
            playing = false
            status = "Playback unavailable"
        }
    }

    fun onPlayPause() = transport {
        if (playing) {
            player.pause()
            playing = false
        } else {
            val uri = current?.uri ?: return@transport
            val from = if (playhead in selStart until selEnd) playhead else selStart
            player.play(uri, from)
            playing = true
        }
    }

    // Leaving the foreground: Android would silence the microphone, so stop and keep the recording
    // (published like a normal Stop), and pause playback. Not a background-recording service.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player, recorder, drafts) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (playing) {
                        try { player.pause() } catch (c: CancellationException) { throw c } catch (_: Exception) {
                            playbackReady = false
                        }
                        playing = false
                    }
                    when (backgroundRecordingAction(recording, recorderStarting, recorderBusy)) {
                        BackgroundRecordingAction.STOP_NOW -> stopForBackground()
                        BackgroundRecordingAction.STOP_AFTER_START -> stopWhenStarted = true
                        BackgroundRecordingAction.NONE -> {}
                    }
                }
                Lifecycle.Event.ON_START -> stopWhenStarted = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun onRingtone() {
        val af = current ?: return
        if (!Ringtones.canWrite(context)) {
            context.startActivity(Ringtones.manageWriteSettingsIntent(context))
            status = "Grant 'Modify system settings', then tap Ringtone again."
            return
        }
        scope.launch {
            val owned = OperationFiles()
            try {
                busy = true
                status = "Setting ringtone…"
                val cached = AudioFiles.resolveToCache(context, af, owned)
                when (val r = runCommitted { Ringtones.setAsDefault(context, cached, af.displayName, srcExt()) }) {
                    is Ringtones.Result.Ok -> status = "Ringtone set: ${r.name}"
                    is Ringtones.Result.NeedsPermission -> status = "Permission needed to set ringtone."
                    is Ringtones.Result.Error -> status = "Ringtone failed: ${r.message}"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                status = "Ringtone failed: ${t.message}"
            } finally {
                owned.deleteAll()
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loadInto(uri) { af -> "Loaded ${af.displayName} • ${fmt(af.durationMs)} • ${af.sampleRate} Hz" }
    }

    val secondPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val op = pendingMulti
        pendingMulti = null
        if (uri == null || op == null) return@rememberLauncherForActivityResult
        when (op) {
            EditOp.MIX -> runDual("Mix", srcExt(), uri) { a, b, o -> Effects.mix(listOf(a, b), o) }
            EditOp.CONCAT -> runDual("Concat", srcExt(), uri) { a, b, o -> Effects.concat(listOf(a, b), o) }
            else -> {}
        }
    }

    fun onTap(op: EditOp) {
        when {
            op == EditOp.TRIM -> {
                val af = current ?: return
                // Capture the range now; the selection stays draggable while the job resolves input.
                val start = selStart
                val end = selEnd
                if (!isTrimSelectionValid(start, end, af.durationMs)) {
                    status = "Select at least ${MIN_TRIM_SELECTION_MS / 1000.0} s of audio to trim."
                    return
                }
                runSingle("Trim", srcExt(), totalMs = end - start) { s, o -> Effects.trimEncode(s, o, start, end) }
            }
            op == EditOp.VOCAL -> runSingle("Vocal Remove", srcExt()) { s, o ->
                Effects.vocalRemoveCenter(s, o)
            }
            op.needsSecondFile -> {
                pendingMulti = op
                secondPicker.launch(arrayOf("audio/*", "video/*"))
            }
            op.needsParams -> pendingOp = op
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EditorHeader()

            FileSourceCard(
                file = current,
                busy = busy,
                recording = recording,
                recordElapsedMs = recordElapsed * 1000,
                onOpen = { picker.launch(arrayOf("audio/*", "video/*")) },
                onRecord = { onRecordTap() },
            )

            recoveryNotice?.let { Text(it, color = Amber) }
            if (draftList.isNotEmpty()) {
                DraftRecoveryCard(
                    drafts = draftList,
                    enabled = !busy && !recording && !recorderBusy,
                    onLoad = { openDraft(it) },
                    onSave = { publishExistingDraft(it) },
                    onDiscard = { discardDraft(it) },
                )
            }

            WaveformWorkspace(
                waveform = waveform,
                selStart = selStart,
                selEnd = selEnd,
                playhead = playhead,
                onSelectionChange = { start, end ->
                    selStart = start
                    selEnd = end
                },
            )

            TransportStrip(
                enabled = playbackReady && current?.uri?.let { player.readyFor(it) } == true && !busy && !recording,
                playing = playing,
                selStart = selStart,
                selEnd = selEnd,
                playhead = playhead,
                onSeekBack = {
                    transport {
                        val target = (playhead - 10_000L).coerceAtLeast(selStart)
                        current?.uri?.let { player.seekTo(it, target) }
                        playhead = target
                    }
                },
                onPlayPause = { onPlayPause() },
                onSeekForward = {
                    transport {
                        val target = (playhead + 10_000L).coerceAtMost(selEnd)
                        current?.uri?.let { player.seekTo(it, target) }
                        playhead = target
                    }
                },
            )

            StatusBanner(status = status, busy = busy)

            Text(
                text = "TOOLS",
                style = MaterialTheme.typography.labelLarge,
                color = TextLo,
                modifier = Modifier.padding(start = 2.dp),
            )

            ToolGrid(
                enabled = current != null && !busy && !recording,
                onTap = { onTap(it) },
            )

            OutputCard(
                file = current,
                enabled = current != null && !busy && !recording,
                onRingtone = { onRingtone() },
            )

            Spacer(Modifier.height(4.dp))
        }
    }

    val op = pendingOp
    if (op != null) {
        OpDialog(
            op = op,
            onDismiss = { pendingOp = null },
        ) { params ->
            pendingOp = null
            onParams(op, params)
        }
    }

}

@Composable
private fun EditorHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(12.dp),
            color = SignalGlow,
            border = BorderStroke(1.dp, Signal.copy(alpha = 0.35f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = Signal,
                    modifier = Modifier.size(25.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Resound", style = MaterialTheme.typography.titleLarge)
            Text("Audio editor", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FileSourceCard(
    file: AudioFile?,
    busy: Boolean,
    recording: Boolean,
    recordElapsedMs: Long,
    onOpen: () -> Unit,
    onRecord: () -> Unit,
) {
    ResoundCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = PanelHi,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.AudioFileIcon, null, tint = Signal)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file?.displayName ?: "No file loaded",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = file?.let {
                            "${it.sampleRate / 1000f} kHz  •  ${if (it.isStereo) "Stereo" else "Mono"}  •  ${fmt(it.durationMs)}"
                        } ?: "Open audio or record something new",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onOpen,
                    enabled = !busy && !recording,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open")
                }
                FilledTonalButton(
                    onClick = onRecord,
                    enabled = !busy,
                    colors = if (recording) {
                        ButtonDefaults.filledTonalButtonColors(containerColor = Amber, contentColor = Color.Black)
                    } else ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (recording) Icons.Default.Stop else Icons.Default.Mic,
                        null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (recording) "Stop ${fmt(recordElapsedMs)}" else "Record")
                }
            }
        }
    }
}

@Composable
private fun WaveformWorkspace(
    waveform: Waveform?,
    selStart: Long,
    selEnd: Long,
    playhead: Long,
    onSelectionChange: (Long, Long) -> Unit,
) {
    ResoundCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("WAVEFORM", style = MaterialTheme.typography.labelLarge, color = TextLo)
                Text(
                    when (waveform?.provenance) {
                        WaveformProvenance.UNAVAILABLE -> "Waveform unavailable"
                        WaveformProvenance.SYNTHETIC -> "Synthetic preview"
                        WaveformProvenance.REAL -> "Selection ${fmt(selEnd - selStart)}"
                        null -> "Waiting for audio"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(InkRaised, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (waveform != null && waveform.provenance != WaveformProvenance.UNAVAILABLE) {
                    WaveformView(
                        waveform = waveform,
                        selStartMs = selStart,
                        selEndMs = selEnd,
                        playheadMs = playhead,
                        onSelectionChange = onSelectionChange,
                    )
                } else {
                    Column(
                        modifier = Modifier.height(168.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Default.GraphicEq, null, tint = TextLo, modifier = Modifier.size(42.dp))
                        Text(if (waveform != null) "Waveform unavailable" else "Your waveform will appear here", color = TextMid, modifier = Modifier.padding(top = 10.dp))
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
    ResoundCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmt(selStart), style = TimecodeStyle, color = TextLo)
                Text(
                    fmtDetailed(playhead),
                    style = TimecodeStyle.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize),
                    color = Signal,
                )
                Text(fmt(selEnd), style = TimecodeStyle, color = TextLo)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onSeekBack, enabled = enabled) {
                    Icon(Icons.Default.Replay10, "Back 10 seconds")
                }
                Spacer(Modifier.width(18.dp))
                FilledIconButton(
                    onClick = onPlayPause,
                    enabled = enabled,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Pause" else "Play",
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(18.dp))
                IconButton(onClick = onSeekForward, enabled = enabled) {
                    Icon(Icons.Default.Forward10, "Forward 10 seconds")
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(status: String, busy: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = InkRaised,
        border = BorderStroke(1.dp, Line),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = TextMid)
        }
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
private fun ToolGrid(enabled: Boolean, onTap: (EditOp) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        toolSpecs.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { item ->
                    ToolTile(
                        spec = item,
                        enabled = enabled && item.op != null,
                        onClick = { item.op?.let(onTap) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolTile(
    spec: ToolSpec,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.aspectRatio(1.18f),
        shape = RoundedCornerShape(14.dp),
        color = PanelHi,
        border = BorderStroke(1.dp, Line),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                spec.icon,
                null,
                tint = if (enabled) Signal else TextLo.copy(alpha = 0.55f),
                modifier = Modifier.size(24.dp),
            )
            Text(
                spec.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) TextHi else TextLo.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}

@Composable
private fun OutputCard(file: AudioFile?, enabled: Boolean, onRingtone: () -> Unit) {
    ResoundCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(12.dp),
                color = PanelHi,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AudioFileIcon, null, tint = Signal)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("OUTPUT", style = MaterialTheme.typography.labelLarge, color = TextLo)
                Text("Music/Resound", style = MaterialTheme.typography.titleMedium)
                Text(
                    file?.let { "${it.sampleRate} Hz  •  ${if (it.isStereo) "Stereo" else "Mono"}" }
                        ?: "Processed files save here",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onRingtone, enabled = enabled) {
                Icon(Icons.Default.Notifications, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ringtone")
            }
        }
    }
}

private fun fmtDetailed(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = ms % 1000
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}


@Composable
private fun DraftRecoveryCard(
    drafts: List<RecordingRecoveryAdmission.Candidate>,
    enabled: Boolean,
    onLoad: (RecordingRecoveryAdmission.Candidate) -> Unit,
    onSave: (RecordingRecoveryAdmission.Candidate) -> Unit,
    onDiscard: (RecordingRecoveryAdmission.Candidate) -> Unit,
) {
    ResoundCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Unsaved recordings", fontWeight = FontWeight.Bold)
            drafts.forEach { f ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(f.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onLoad(f) }, enabled = enabled) { Text("Open") }
                        OutlinedButton(onClick = { onSave(f) }, enabled = enabled) { Text("Save") }
                        OutlinedButton(onClick = { onDiscard(f) }, enabled = enabled) { Text("Discard") }
                    }
                }
            }
        }
    }
}
