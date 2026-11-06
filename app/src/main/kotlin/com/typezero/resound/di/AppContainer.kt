/*
 * file:    AppContainer.kt
 * author:  Typezer∅
 * version: 0.3.1
 * desc:    Manual DI container (no Hilt/Koin per repo convention). Holds the
 *          shared singletons feature screens pull from.
 */
package com.typezero.resound.di

import android.content.Context
import com.typezero.resound.core.audio.MediaCodecWaveformExtractor
import com.typezero.resound.core.audio.WaveformExtractor
import com.typezero.resound.core.ffmpeg.FFmpegKitRunner
import com.typezero.resound.core.ffmpeg.FFmpegRunner
import com.typezero.resound.feature.record.AndroidRecorder
import com.typezero.resound.feature.record.Recorder

class AppContainer(private val appContext: Context) {
    // Real engine — the ffmpeg-kit AAR now resolves from Maven Central.
    val ffmpeg: FFmpegRunner by lazy { FFmpegKitRunner() }

    // Real MediaCodec PCM decode; failures return explicit waveform-unavailable state.
    val waveformExtractor: WaveformExtractor by lazy {
        MediaCodecWaveformExtractor(appContext)
    }

    val playerFactory: com.typezero.resound.core.audio.AudioPlayerFactory =
        com.typezero.resound.core.audio.PlayerRuntimeProvider.playerFactory()

    val recorder: Recorder by lazy { AndroidRecorder(appContext) }

    // Application scope: outlives screens so recorder stop/cleanup cannot be cancelled by navigation.
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    val recordingController: com.typezero.resound.feature.record.RecordingController by lazy {
        com.typezero.resound.feature.record.RecordingController(recorder, appScope, kotlinx.coroutines.Dispatchers.IO)
    }

    // Separate Main owner for Compose state; file/codec/recovery work dispatches to IO.
    private val timelineScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )
    // Automatic arrangement recovery outlives tabs and is restored from app-private metadata.
    val timelineSession: com.typezero.resound.feature.timeline.TimelineSession by lazy {
        com.typezero.resound.feature.timeline.TimelineDraftBinding.attach(appContext, timelineScope, waveformExtractor)
    }
}
