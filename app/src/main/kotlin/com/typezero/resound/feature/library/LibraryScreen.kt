package com.typezero.resound.feature.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.typezero.resound.core.io.Outputs
import com.typezero.resound.ui.components.ResoundHairline
import com.typezero.resound.ui.components.ResoundIconAction
import com.typezero.resound.ui.components.ResoundAction
import com.typezero.resound.ui.components.ResoundPanel
import com.typezero.resound.ui.components.ResoundScreenHeader
import com.typezero.resound.ui.theme.Accent
import com.typezero.resound.ui.theme.IconInfo
import com.typezero.resound.ui.theme.Line
import com.typezero.resound.ui.theme.TextHi
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

    LibraryWorkspace(
        exports = exports,
        permissionGranted = permissionGranted,
        loading = loading,
        error = error,
        folder = Outputs.musicDirectoryDisplay,
        onRefresh = { refreshKey++ },
        onAllowAccess = { permissionLauncher.launch(audioPermission()) },
        onOpen = { item -> onOpenInEditor(item.uri) },
        onShare = { item -> shareAudio(context, item) },
        modifier = modifier,
    )
}

/** Which body the Library shows; same precedence as before the redesign. */
internal enum class LibraryPhase { PERMISSION, LOADING, ERROR, EMPTY, LIST }

internal fun libraryPhase(permissionGranted: Boolean, loading: Boolean, error: String?, count: Int): LibraryPhase = when {
    !permissionGranted -> LibraryPhase.PERMISSION
    loading -> LibraryPhase.LOADING
    error != null -> LibraryPhase.ERROR
    count == 0 -> LibraryPhase.EMPTY
    else -> LibraryPhase.LIST
}

internal fun librarySubtitle(count: Int, folder: String): String = when (count) {
    0 -> "Exports in $folder"
    1 -> "1 export · $folder"
    else -> "$count exports · $folder"
}

/** Short format label for the row badge, from the file extension. */
internal fun exportFormat(displayName: String): String {
    val ext = displayName.substringAfterLast('.', "")
    return if (ext.isEmpty() || ext.length > 5 || !ext.all(Char::isLetterOrDigit)) "AUDIO" else ext.uppercase(Locale.US)
}

/**
 * Display-only Library body. The Scaffold in MainActivity already pads for the status bar and the
 * bottom navigation, so no window-inset padding is applied here (it was applied twice before).
 */
@Composable
internal fun LibraryWorkspace(
    exports: List<LibraryAudio>,
    permissionGranted: Boolean,
    loading: Boolean,
    error: String?,
    folder: String,
    onRefresh: () -> Unit,
    onAllowAccess: () -> Unit,
    onOpen: (LibraryAudio) -> Unit,
    onShare: (LibraryAudio) -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = libraryPhase(permissionGranted, loading, error, exports.size)
    Column(modifier.fillMaxSize()) {
        ResoundScreenHeader(
            icon = Icons.Outlined.LibraryMusic,
            title = "Library",
            subtitle = librarySubtitle(if (phase == LibraryPhase.LIST) exports.size else 0, folder),
        ) {
            ResoundIconAction(Icons.Outlined.Refresh, "Refresh library", onRefresh, enabled = !loading && permissionGranted)
        }
        ResoundHairline()
        when (phase) {
            LibraryPhase.PERMISSION -> LibraryNotice(
                icon = Icons.Outlined.LockOpen,
                title = "Audio access required",
                body = "Allow Resound to list the audio files in $folder.",
            ) {
                ResoundAction("Allow access", Icons.Outlined.LockOpen, onAllowAccess)
            }
            LibraryPhase.LOADING -> LibraryNotice(icon = null, title = "Scanning $folder…", body = null)
            LibraryPhase.ERROR -> LibraryNotice(
                icon = Icons.Outlined.ErrorOutline,
                title = "Library unavailable",
                body = error ?: "Could not read $folder",
                iconTint = MaterialTheme.colorScheme.error,
            )
            LibraryPhase.EMPTY -> LibraryNotice(
                icon = Icons.Outlined.LibraryMusic,
                title = "No exports yet",
                body = "Files exported from Editor or Multitrack will appear here automatically.",
            )
            LibraryPhase.LIST -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(exports, key = { _, item -> item.uri.toString() }) { index, item ->
                    LibraryRow(item, onOpen = { onOpen(item) }, onShare = { onShare(item) })
                    if (index < exports.lastIndex) ResoundHairline(startIndent = 72.dp)
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

/** 64dp file row: the main area opens the file in the Editor; the share action is a separate 48dp target. */
@Composable
private fun LibraryRow(item: LibraryAudio, onOpen: () -> Unit, onShare: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .clickable(onClickLabel = "Open in Editor", role = Role.Button, onClick = onOpen)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatBadge(exportFormat(item.displayName))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    fontWeight = FontWeight.Medium,
                    color = TextHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    exportDetails(item),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = TextMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ResoundIconAction(Icons.Outlined.Share, "Share ${item.displayName}", onShare)
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun FormatBadge(format: String) {
    Box(
        modifier = Modifier
            .widthIn(min = 44.dp)
            .heightIn(min = 28.dp)
            .border(1.dp, Line, RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            format,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
            fontWeight = FontWeight.SemiBold,
            color = TextMid, // the format is information, not a control
            maxLines = 1,
        )
    }
}

/** Compact inline panel for permission, loading, error and empty states. */
@Composable
private fun LibraryNotice(
    icon: ImageVector?,
    title: String,
    body: String?,
    iconTint: Color = IconInfo,
    action: (@Composable () -> Unit)? = null,
) {
    ResoundPanel(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            if (icon == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = Accent,
                )
            } else {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.sp), color = TextHi)
                if (body != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(body, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp), color = TextMid)
                }
                if (action != null) {
                    Spacer(Modifier.height(10.dp))
                    action()
                }
            }
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

/** Row detail line; the format is shown by the badge, so it is not repeated here. */
internal fun exportDetails(item: LibraryAudio): String = exportDetails(item.durationMs, item.sizeBytes, item.modifiedSeconds)

internal fun exportDetails(durationMs: Long, sizeBytes: Long, modifiedSeconds: Long): String {
    val duration = formatDuration(durationMs)
    val size = formatSize(sizeBytes)
    val date = if (modifiedSeconds > 0) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(modifiedSeconds * 1000))
    } else "Unknown date"
    return "$duration  ·  $size  ·  $date"
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

internal fun formatSize(bytes: Long): String = when {
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
