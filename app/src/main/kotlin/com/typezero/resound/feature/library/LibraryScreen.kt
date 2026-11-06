package com.typezero.resound.feature.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.typezero.resound.ui.components.ResoundCard
import com.typezero.resound.core.io.Outputs
import com.typezero.resound.ui.theme.PanelHi
import com.typezero.resound.ui.theme.Signal
import com.typezero.resound.ui.theme.TextLo
import com.typezero.resound.ui.theme.TextMid
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    onOpenInEditor: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var permissionGranted by remember { mutableStateOf(hasAudioPermission(context)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var exports by remember { mutableStateOf<List<LibraryAudio>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (granted) refreshKey++
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(audioPermission())
    }

    LaunchedEffect(permissionGranted, refreshKey) {
        if (!permissionGranted) return@LaunchedEffect
        loading = true
        error = null
        runCatching { ResoundLibrary.load(context) }
            .onSuccess { exports = it }
            .onFailure { error = it.message ?: "Could not read ${Outputs.musicDirectoryDisplay}" }
        loading = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Library", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (exports.isEmpty()) "Your Resound exports" else "${exports.size} exports in ${Outputs.musicDirectoryDisplay}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextLo,
                )
            }
            FilledTonalIconButton(onClick = { refreshKey++ }, enabled = !loading && permissionGranted) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh library")
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            !permissionGranted -> PermissionCard(onGrant = { permissionLauncher.launch(audioPermission()) })
            loading -> LoadingCard()
            error != null -> MessageCard(
                title = "Library unavailable",
                body = error ?: "Could not read ${Outputs.musicDirectoryDisplay}",
            )
            exports.isEmpty() -> EmptyCard()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(exports, key = { it.uri.toString() }) { item ->
                    ExportCard(
                        item = item,
                        onOpen = { onOpenInEditor(item.uri) },
                        onShare = { shareAudio(context, item) },
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun ExportCard(item: LibraryAudio, onOpen: () -> Unit, onShare: () -> Unit) {
    ResoundCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.small,
                color = PanelHi,
            ) {
                Icon(
                    Icons.Outlined.AudioFile,
                    contentDescription = null,
                    tint = Signal,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    exportDetails(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, contentDescription = "Share ${item.displayName}", tint = Signal)
            }
        }
    }
}

@Composable
private fun EmptyCard() {
    ResoundCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.LibraryMusic, contentDescription = null, tint = Signal)
            Spacer(Modifier.height(14.dp))
            Text("No exports yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Files exported from Editor or Multitrack will appear here automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextLo,
            )
        }
    }
}

@Composable
private fun LoadingCard() {
    ResoundCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Text("Scanning ${Outputs.musicDirectoryDisplay}…", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    ResoundCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onGrant)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Audio access required", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap to allow Resound to display the audio files in ${Outputs.musicDirectoryDisplay}.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextLo,
            )
        }
    }
}

@Composable
private fun MessageCard(title: String, body: String) {
    ResoundCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = TextLo)
        }
    }
}

private fun hasAudioPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

private fun audioPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.READ_MEDIA_AUDIO
} else {
    Manifest.permission.READ_EXTERNAL_STORAGE
}

private fun exportDetails(item: LibraryAudio): String {
    val ext = item.displayName.substringAfterLast('.', "audio").uppercase(Locale.US)
    val duration = formatDuration(item.durationMs)
    val size = formatSize(item.sizeBytes)
    val date = if (item.modifiedSeconds > 0) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.modifiedSeconds * 1000))
    } else "Unknown date"
    return "$ext  •  $duration  •  $size  •  $date"
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(Locale.US, bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

private fun shareAudio(context: android.content.Context, item: LibraryAudio) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType
        putExtra(Intent.EXTRA_STREAM, item.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ${item.displayName}"))
}
