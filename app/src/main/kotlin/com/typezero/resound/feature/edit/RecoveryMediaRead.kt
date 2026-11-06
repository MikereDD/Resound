package com.typezero.resound.feature.edit

import android.media.MediaExtractor
import android.media.MediaFormat
import com.typezero.resound.feature.record.RecordingRecoveryAdmission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Narrow action-time read. Never called by discovery/inspection. */
internal class RecoveryMediaRead(private val reader: () -> Reader = { AndroidReader() }) {
    interface Reader : AutoCloseable { fun inspect(file: File): RecoveryReadOutcome }

    suspend fun check(file: File, lease: RecordingRecoveryAdmission.Lease): RecoveryReadOutcome =
        withContext(Dispatchers.IO) {
            val owned = reader()
            var result = RecoveryReadOutcome.UNVERIFIED
            try {
                result = owned.inspect(file)
            } catch (c: kotlinx.coroutines.CancellationException) { throw c
            } catch (_: Exception) {
                // Access denial, unsupported containers and vendor/parser errors do not
                // establish damaged bytes. Keep original-copy Save available.
                result = RecoveryReadOutcome.UNVERIFIED
            } finally {
                try { owned.close() } catch (t: Throwable) {
                    // Must happen HERE on IO, before a cancelled dispatcher return can discard
                    // the result. A display flag on Main cannot protect native ownership.
                    lease.retainIndeterminate("Recovery media read cleanup uncertain: ${t.message}")
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    result = RecoveryReadOutcome.CLEANUP_UNCERTAIN
                }
            }
            result
        }

    companion object {
        // Fixed upper allocation, never trust KEY_MAX_INPUT_SIZE from damaged media.
        private const val MAX_FIRST_SAMPLE_BYTES = 1024 * 1024

        fun classify(audioTrack: Boolean, durationUs: Long?, firstSampleBytes: Int?): RecoveryReadOutcome = when {
            !audioTrack -> RecoveryReadOutcome.NO_USABLE_MEDIA
            durationUs == null -> RecoveryReadOutcome.UNVERIFIED
            durationUs <= 0 -> RecoveryReadOutcome.NO_USABLE_MEDIA
            firstSampleBytes == null || firstSampleBytes > MAX_FIRST_SAMPLE_BYTES -> RecoveryReadOutcome.UNVERIFIED
            firstSampleBytes <= 0 -> RecoveryReadOutcome.NO_USABLE_MEDIA
            else -> RecoveryReadOutcome.UNVERIFIED // Encoded bytes are NOT decoded playback proof.
        }
    }

    /** All acquisition happens in inspect, after the wrapper owns this cleanup object on IO. */
    private class AndroidReader : Reader {
        private var input: java.io.FileInputStream? = null
        private var extractor: MediaExtractor? = null

        override fun inspect(file: File): RecoveryReadOutcome {
            val stream = java.io.FileInputStream(file).also { input = it } // Read-only local FD.
            val media = MediaExtractor().also { extractor = it }
            media.setDataSource(stream.fd)
            var audio = false
            var missingDuration = false
            for (track in 0 until media.trackCount) {
                val format = media.getTrackFormat(track)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                audio = true
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
                if (duration == null) { missingDuration = true; continue }
                if (duration <= 0L) continue
                media.selectTrack(track)
                val size = media.readSampleData(java.nio.ByteBuffer.allocate(MAX_FIRST_SAMPLE_BYTES), 0)
                return classify(true, duration, size)
            }
            return classify(audio, if (missingDuration) null else 0L, null)
        }

        override fun close() {
            // Attempt both independently even when the first release fails.
            var failure: Throwable? = null
            try { extractor?.release() } catch (t: Throwable) { failure = t }
            finally {
                try { input?.close() } catch (t: Throwable) {
                    if (failure == null) failure = t else failure.addSuppressed(t)
                }
            }
            failure?.let { throw it }
        }
    }
}
