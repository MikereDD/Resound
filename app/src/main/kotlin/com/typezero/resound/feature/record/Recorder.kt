/*
 * file:    Recorder.kt
 * author:  Mike Redd (typezero)
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
    fun stop(): String?   // returns the recorded file path, or null on failure
    val isRecording: Boolean
}

class AndroidRecorder(private val context: Context) : Recorder {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override var isRecording: Boolean = false
        private set

    override fun start(outputPath: String) {
        check(!isRecording && recorder == null) { "Recording is already active" }
        val file = File(outputPath)
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            outputFile = file
            isRecording = true
        } catch (failure: Throwable) {
            try { r.release() } catch (_: Throwable) { }
            file.delete()
            throw failure
        }
    }

    override fun stop(): String? {
        val r = recorder ?: return null
        val file = outputFile
        var success = false
        try {
            r.stop()
            success = true
            return file?.absolutePath
        } catch (_: RuntimeException) {
            return null
        } finally {
            try { r.release() } finally {
                recorder = null
                outputFile = null
                isRecording = false
                if (!success) file?.delete()
            }
        }
    }
}
