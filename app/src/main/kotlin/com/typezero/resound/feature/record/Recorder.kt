/*
 * file:    Recorder.kt
 * author:  Typezer∅
 * version: 0.4.0
 * desc:    Voice recorder. AndroidRecorder wraps MediaRecorder for one-tap
 *          capture to an AAC/m4a file. The recorded file is loaded straight
 *          back into the editor so it can be trimmed, set as a ringtone, etc.
 */
package com.typezero.resound.feature.record

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

interface Recorder {
    fun start(outputPath: String)
    fun stop(): String?   // compatibility: stopped audio, NOT proof of native termination
    val isRecording: Boolean
    /** Explicit backend acknowledgement; legacy implementations fail closed. */
    val terminationAcknowledged: Boolean get() = false
    fun stopOutcome(): RecorderOutcome = RecorderOutcome(stop(), terminationAcknowledged)
}

/** Audio finalization and native termination are independent predicates. */
data class RecorderOutcome(val path: String?, val terminated: Boolean, val failure: Throwable? = null)

class AndroidRecorder(private val context: Context) : Recorder {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override var isRecording: Boolean = false
        private set
    override var terminationAcknowledged: Boolean = true
        private set

    override fun start(outputPath: String) {
        check(terminationAcknowledged && !isRecording && recorder == null) { "Recording is active or termination is uncertain" }
        val file = File(outputPath)
        // Constructor failure can leave native allocation uncertain too; only release acknowledges it.
        terminationAcknowledged = false
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        // Own the native handle before configuration can fail. A failed release stays guarded.
        recorder = r
        outputFile = file
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            isRecording = true
        } catch (failure: Throwable) {
            try {
                r.release()
                terminationAcknowledged = true
                recorder = null
                outputFile = null
            } catch (releaseFailure: Throwable) {
                failure.addSuppressed(releaseFailure)
            }
            // Failed/partial capture bytes are user drafts, never deletion repair.
            throw failure
        }
    }

    override fun stop(): String? = stopOutcome().path

    override fun stopOutcome(): RecorderOutcome {
        val r = recorder ?: return RecorderOutcome(null, terminationAcknowledged)
        val file = outputFile
        val result = stopAndReleaseOutcome(stop = { r.stop() }, release = { r.release() })
        terminationAcknowledged = result.released
        isRecording = false // UI observation only, not a termination acknowledgement
        if (result.released) {
            recorder = null
            outputFile = null
        }
        return RecorderOutcome(if (result.stopped) file?.absolutePath else null, result.released, result.failure)
    }
}

internal data class StopReleaseOutcome(val stopped: Boolean, val released: Boolean, val failure: Throwable?)

/** Both operations are attempted; successful stop never hides uncertain release. */
internal fun stopAndReleaseOutcome(stop: () -> Unit, release: () -> Unit): StopReleaseOutcome {
    var stopped = false
    var released = false
    var failure: Throwable? = null
    try { stop(); stopped = true } catch (t: Throwable) { failure = t }
    try { release(); released = true } catch (t: Throwable) {
        val prior = failure
        if (prior == null) failure = t else prior.addSuppressed(t)
    }
    return StopReleaseOutcome(stopped, released, failure)
}

/** Compatibility predicate: stopped audio is retained even if release failed. Not quiescence. */
internal fun stopAndRelease(stop: () -> Unit, release: () -> Unit): Boolean =
    stopAndReleaseOutcome(stop, release).stopped
