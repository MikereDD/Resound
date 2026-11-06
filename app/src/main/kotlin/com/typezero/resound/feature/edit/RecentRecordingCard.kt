package com.typezero.resound.feature.edit

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typezero.resound.ui.components.ResoundCard
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.typezero.resound.ui.theme.Amber
import com.typezero.resound.ui.theme.Signal
import com.typezero.resound.ui.theme.TextHi
import com.typezero.resound.ui.theme.TextLo
import com.typezero.resound.ui.theme.TextMid
import java.text.DateFormat
import java.util.Date

/** Deliberately no synthetic waveform or History control: this is one published reference. */
@Composable
fun RecentRecordingCard(recording: RecentRecording, notice: RecentRecordingNotice?,
                        onReopen: () -> Unit, onClear: () -> Unit) {
    val completed = remember(recording.completedAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(recording.completedAtMs))
    }
    ResoundCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Signal, modifier = Modifier.size(20.dp))
                Text("RECENT RECORDING", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold, color = TextLo)
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(recording.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = TextHi,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Completed $completed", style = MaterialTheme.typography.bodySmall, color = TextMid)
                Text(recentRecordingMetadata(recording), style = MaterialTheme.typography.bodySmall, color = TextMid)
            }
            if (notice != null) {
                Column(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("File unavailable", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, color = Amber)
                    Text("This recording may have been moved or deleted, or access may be unavailable.",
                        style = MaterialTheme.typography.bodyMedium, color = TextMid)
                    Text("Clear Recent removes only this shortcut. Audio and recovery drafts are kept.",
                        style = MaterialTheme.typography.bodyMedium, color = TextMid)
                    if (notice.clearFailed) {
                        Text("Could not clear Recent. Please try again.",
                            style = MaterialTheme.typography.bodyMedium, color = Amber)
                    }
                }
                OutlinedButton(onClick = onReopen, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Retry", fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Clear Recent", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(onClick = onReopen, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Reopen", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
