package com.typezero.resound.feature.timeline

import android.os.Looper
import android.os.StrictMode
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text

/** Read only: no disk I/O, no policy mutation, no broadcast/API endpoint and no source URI output. */
@Composable
internal fun TimelineDiagnostics(session: TimelineSession) {
    val onMain = Looper.myLooper() == Looper.getMainLooper()
    val policy = StrictMode.getThreadPolicy().toString()
    Column {
        Text("DEV TEST — read-only diagnostics; main=$onMain; policy=$policy")
        Text("Policy display is NOT a StrictMode positive control.")
        session.tracks.forEach { track ->
            track.clips.forEach { clip ->
                Text("clip=${clip.id} startMs=${clip.startMs} sourceInMs=${clip.sourceInMs} " +
                    "sourceOutMs=${clip.sourceOutMs} lengthMs=${clip.lengthMs} available=${clip.sourceAvailable}")
            }
        }
    }
}
