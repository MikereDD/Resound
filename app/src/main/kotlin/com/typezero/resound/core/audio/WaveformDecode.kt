package com.typezero.resound.core.audio

import kotlinx.coroutines.ensureActive

/** Decode boundary shared by the real codec adapter and host fault injection. */
class WaveformDecode {
    fun complete(frames: Long, mins: FloatArray, maxs: FloatArray, durationMs: Long): Waveform {
        check(frames > 0) { "Decoder produced no PCM frames" }
        for (b in mins.indices) if (mins[b] > maxs[b]) { mins[b] = 0f; maxs[b] = 0f }
        return Waveform(mins, maxs, durationMs)
    }
    suspend fun extract(decode: suspend () -> Waveform, unavailable: () -> Waveform): Waveform {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val result = try { decode() } catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (_: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            unavailable()
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return result
    }
}
