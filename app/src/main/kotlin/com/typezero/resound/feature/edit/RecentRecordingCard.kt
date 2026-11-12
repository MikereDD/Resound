package com.typezero.resound.feature.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.typezero.resound.ui.theme.Amber
import com.typezero.resound.ui.theme.Line
import com.typezero.resound.ui.theme.Panel
import com.typezero.resound.ui.components.ResoundAction
import com.typezero.resound.ui.components.resoundOutlinedTextColors
import com.typezero.resound.ui.theme.IconInfo
import com.typezero.resound.ui.theme.TextHi
import com.typezero.resound.ui.theme.TextLo
import com.typezero.resound.ui.theme.TextMid
import java.text.DateFormat
import java.util.Date

/** Deliberately no synthetic waveform or History control: this is one published reference. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecentRecordingCard(recording: RecentRecording, notice: RecentRecordingNotice?,
                        onReopen: () -> Unit, onClear: () -> Unit) {
    val completed = remember(recording.completedAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(recording.completedAtMs))
    }
    val titleStyle = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val bodyStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        border = BorderStroke(1.dp, Line),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = IconInfo, modifier = Modifier.size(18.dp))
                Text("RECENT RECORDING", style = labelStyle,
                    fontWeight = FontWeight.SemiBold, color = TextLo)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(recording.name, style = titleStyle,
                    fontWeight = FontWeight.SemiBold, color = TextHi,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Completed $completed", style = bodyStyle, color = TextLo)
                Text(recentRecordingMetadata(recording), style = bodyStyle, color = TextLo)
            }
            if (notice != null) {
                Column(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("File unavailable", style = titleStyle,
                        fontWeight = FontWeight.SemiBold, color = Amber)
                    Text("This recording may have been moved or deleted, or access may be unavailable.",
                        style = bodyStyle, color = TextMid)
                    Text("Clear Recent removes only this shortcut. Audio and recovery drafts are kept.",
                        style = bodyStyle, color = TextMid)
                    if (notice.clearFailed) {
                        Text("Could not clear Recent. Please try again.",
                            style = bodyStyle, color = Amber)
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedButton(onClick = onReopen, modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp), colors = resoundOutlinedTextColors(),
                        border = BorderStroke(1.dp, Line)) {
                        Text("Retry", style = labelStyle, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Clear Recent", style = labelStyle, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                ResoundAction("Reopen", Icons.Default.FolderOpen, onReopen)
            }
        }
    }
}
