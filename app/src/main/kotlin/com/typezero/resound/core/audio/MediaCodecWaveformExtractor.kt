/*
 * file:    MediaCodecWaveformExtractor.kt
 * author:  Mike Redd (typezero)
 * version: 0.2.1
 * desc:    Real waveform extraction. Decodes the source to PCM with
 *          MediaExtractor + MediaCodec and folds the samples into
 *          targetBuckets (min, max) pairs as it streams — so memory stays flat
 *          regardless of file length. Decode failure is explicitly unavailable;
 *          it never manufactures audio amplitudes. Cancellation propagates.
 *
 *          Notes / limits (fine for v0.2.1, refine later):
 *            - Full-file decode; a 4-min track takes ~a second or two.
 *            - Handles 16-bit and float PCM output; collapses channels by
 *              taking the peak across channels per frame.
 */
package com.typezero.resound.core.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive


class MediaCodecWaveformExtractor(
    private val context: Context,
    private val budgetFactory: () -> DecodeBudget = { DecodeBudget() },
) : WaveformExtractor {

    override suspend fun extract(file: AudioFile, targetBuckets: Int): Waveform =
        withContext(Dispatchers.Default) {
            WaveformDecode().extract({ decode(file, targetBuckets.coerceAtLeast(1)) }) {
                Waveform.unavailable(file.durationMs)
            }
        }

    private suspend fun decode(file: AudioFile, buckets: Int): Waveform {
        val coroutineContext = currentCoroutineContext()
        val budget = budgetFactory()
        coroutineContext.ensureActive()
        return DecodeResources().use { resources ->
            val pfd = context.contentResolver.openFileDescriptor(file.uri, "r")
                ?: throw IllegalStateException("Cannot open audio source")
            resources.defer { pfd.close() }
            val extractor = MediaExtractor()
            resources.defer { extractor.release() }
            extractor.setDataSource(pfd.fileDescriptor)

            var trackIndex = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; inFormat = f; break
                }
            }
            val format = inFormat ?: throw IllegalStateException("No audio track")
            extractor.selectTrack(trackIndex)

            val sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, file.sampleRate)
            var channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, file.channels.coerceAtLeast(1))
            var pcmEncoding = format.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = resources.codec(
                { MediaCodec.createDecoderByType(mime) },
                { it.configure(format, null, null, 0) },
                { it.start() }, { it.stop() }, { it.release() },
            )

            // Estimate total frames so we can map frame index -> bucket as we go.
            val approxFrames = (file.durationMs / 1000.0 * sampleRate).toLong().coerceAtLeast(1)
            val framesPerBucket = (approxFrames / buckets).coerceAtLeast(1)

            val mins = FloatArray(buckets) { Float.POSITIVE_INFINITY }
            val maxs = FloatArray(buckets) { Float.NEGATIVE_INFINITY }
            var frame = 0L

            val info = MediaCodec.BufferInfo()
            var inputDone = false


            fun bucketOf(f: Long) = (f / framesPerBucket).toInt().coerceIn(0, buckets - 1)

            DecodeDrain(budget).run {
                var progressed = false
                var outputDone = false
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                            progressed = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                            progressed = size > 0
                        }
                    }
                }

                coroutineContext.ensureActive()
                budget.check(progressed)
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        channels = of.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = of.intOr(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val before = frame
                            frame = PcmPeakFold.foldBuffer(outBuf, channels, pcmEncoding, frame, mins, maxs, ::bucketOf) {
                                coroutineContext.ensureActive()
                                budget.check()
                            }
                            progressed = progressed || frame > before
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
                DecodeStep(progressed, outputDone)
            }
            WaveformDecode().complete(frame, mins, maxs, file.durationMs)
        }
    }



    private fun MediaFormat.intOr(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}
