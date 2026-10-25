/*
 * file:    AudioFiles.kt
 * author:  Mike Redd (typezero)
 * version: 0.2.0
 * desc:    Bridges Android's storage model to the editor. SAF gives us a
 *          content:// Uri; FFmpeg needs a real filesystem path. This:
 *            1. reads metadata (duration / sample rate / channels) via
 *               MediaExtractor, and the display name via OpenableColumns, and
 *            2. copies the stream into cacheDir so FFmpeg has a path to read.
 */
package com.typezero.resound.core.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AudioFiles {

    /** Read just the metadata needed to drive the editor. */
    suspend fun readMetadata(context: Context, uri: Uri): AudioFile =
        withContext(Dispatchers.IO) {
            val name = queryDisplayName(context, uri) ?: "audio"
            val extractor = MediaExtractor()
            try {
                // PFD must remain open while MediaExtractor reads track formats.
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalArgumentException("Could not open $uri")
                pfd.use {
                        extractor.setDataSource(pfd.fileDescriptor)
                        var fmt: MediaFormat? = null
                        for (i in 0 until extractor.trackCount) {
                            val f = extractor.getTrackFormat(i)
                            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                                fmt = f; break
                            }
                        }
                        val mime = fmt?.getString(MediaFormat.KEY_MIME) ?: "audio/unknown"
                        val durUs = fmt?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                            ?.getLong(MediaFormat.KEY_DURATION) ?: 0L
                        val sampleRate = fmt?.takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }
                            ?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
                        val channels = fmt?.takeIf { it.containsKey(MediaFormat.KEY_CHANNEL_COUNT) }
                            ?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 2

                        AudioFile(
                            uri = uri,
                            displayName = name,
                            durationMs = durUs / 1000,
                            sampleRate = sampleRate,
                            channels = channels,
                            mimeType = mime,
                        )
                }
            } finally {
                extractor.release()
            }
        }

    /**
     * Copy [uri] into cacheDir and return the file, so FFmpeg has a path.
     * The extension is preserved from the display name where possible.
     */
    suspend fun resolveToCache(context: Context, file: AudioFile): File =
        withContext(Dispatchers.IO) {
            val ext = file.displayName.substringAfterLast('.', "")
                .lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "tmp"
            val dst = File.createTempFile("src_", ".$ext", context.cacheDir)
            try {
                context.contentResolver.openInputStream(file.uri).use { input ->
                    requireNotNull(input) { "Could not open stream for ${file.uri}" }
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                dst
            } catch (failure: Throwable) {
                dst.delete()
                throw failure
            }
        }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        return null
    }
}
