package com.typezero.resound.feature.edit

import com.typezero.resound.core.audio.Waveform
import com.typezero.resound.core.audio.WaveformDecode
import com.typezero.resound.core.audio.WaveformProvenance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Accepted-source policy used by Editor, independent of metadata/Android types. */
class PreviewLoad {
    data class Result(val waveform: Waveform?, val ready: Boolean, val message: String?)
    suspend fun load(durationMs: Long, accept: suspend () -> Unit, decode: suspend () -> Waveform,
                     prepare: suspend () -> Unit, isReady: () -> Boolean = { true },
                     onWaveform: (Waveform) -> Unit = {}): Result? {
        currentCoroutineContext().ensureActive()
        if (durationMs <= 0) return null
        accept()
        val waveform = WaveformDecode().extract(decode) { Waveform.unavailable(durationMs) }
        onWaveform(waveform)
        val prepared = try { prepare(); true } catch (c: CancellationException) { throw c } catch (_: Exception) { false }
        val ready = prepared && isReady()
        currentCoroutineContext().ensureActive()
        val message = when {
            !ready -> "Playback unavailable"
            waveform.provenance == WaveformProvenance.UNAVAILABLE -> "Waveform unavailable"
            waveform.provenance == WaveformProvenance.SYNTHETIC -> "Synthetic waveform (preview)"
            else -> null
        }
        return Result(waveform, ready, message)
    }
}
