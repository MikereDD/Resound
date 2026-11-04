package com.typezero.resound.feature.timeline

import android.content.Context
import android.net.Uri
import com.typezero.resound.core.audio.AudioFile
import com.typezero.resound.core.audio.Waveform
import com.typezero.resound.core.audio.WaveformExtractor
import com.typezero.resound.feature.timeline.draft.*
import kotlinx.coroutines.*
import java.io.File

/** Android URI/grant/waveform adapter. The store itself only sees strings and metadata. */
object TimelineDraftBinding {
    fun attach(context: Context, scope: CoroutineScope, extractor: WaveformExtractor): TimelineSession {
        val session = TimelineSession()
        session.startPersistence(
            scope,
            // Factory is invoked by the async owner on IO, including context.filesDir lookup.
            { ArrangementStore(File(context.filesDir, "arrangements/automatic")) },
            Dispatchers.IO,
        ) { draft ->
            withContext(Dispatchers.IO) {
                val recovered = ArrangementRecovery.inspect(draft) { source ->
                    context.contentResolver.openFileDescriptor(Uri.parse(source.uri), "r")?.use { true } ?: false
                }
                draft.tracks.map { track ->
                    val clips = track.clips.map { clip ->
                        val s = clip.source
                        val source = AudioFile(Uri.parse(s.uri), s.displayName, s.durationMs,
                            s.sampleRate, s.channels, s.mimeType)
                        val available = clip.id !in recovered.missingClipIds
                        val empty = Waveform(FloatArray(0), FloatArray(0), s.durationMs)
                        val waveform = if (!available) empty else try {
                            extractor.extract(source, targetBuckets = 600)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            empty // waveform failure must not erase the arrangement or source reference
                        }
                        Clip(clip.id, source, waveform, clip.startMs, clip.sourceInMs,
                            clip.sourceOutMs, sourceAvailable = available)
                    }
                    Track(track.id, track.name, clips, track.muted, track.volume)
                }
            }
        }
        return session
    }
}
