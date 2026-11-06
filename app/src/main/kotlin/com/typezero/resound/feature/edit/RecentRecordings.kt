package com.typezero.resound.feature.edit

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.typezero.resound.core.io.Outputs
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Process-wide state prevents a recreated Editor's startup read racing a newer Stop. */
object RecentRecordings {
    @Volatile private var instance: RecentRecordingController? = null

    fun forContext(context: Context): RecentRecordingController = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            // Resolve filesDir lazily inside controller IO, not during composition.
            RecentRecordingController(RecentRecordingStore { File(app.filesDir, "recent-recording.ref") })
                .also { instance = it }
        }
    }
}

/** Header-only metadata for this normal Stop's published copy. Never decodes/scans audio.
 * Unlike AudioFiles, absent duration/channels have no defaults. M4A/AAC is the actual recorder
 * output configuration; no channel count is configured by AndroidRecorder.
 */
suspend fun describePublishedRecording(
    context: Context,
    publication: Outputs.Published,
    completedAtMs: Long,
): RecentRecording = withContext(Dispatchers.IO) {
    var duration: Long? = null
    var channels: Int? = null
    try {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(publication.uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        duration = (format.getLong(MediaFormat.KEY_DURATION) / 1000)
                            .takeIf { it in 1..31536000000L }
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).takeIf { it in 1..32 }
                    }
                    break
                }
            }
        } finally { extractor.release() }
    } catch (c: CancellationException) { throw c } catch (_: Exception) {
        // Publication was already verified. A metadata read failure must not invent values.
    }
    // A requested name is not provider-confirmed metadata. Preserve the prior Recent on failure.
    val actualName = requireNotNull(publication.actualDisplayName) { "Published filename unavailable" }
    RecentRecording(publication.uri.toString(), actualName,
        completedAtMs, duration, "M4A · AAC", channels)
}
