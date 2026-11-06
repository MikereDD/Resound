package com.typezero.resound.feature.about

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.typezero.resound.core.update.AppUpdater
import com.typezero.resound.core.update.UpdatePolicy
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val updater = remember { AppUpdater(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var status by remember { mutableStateOf("Checking for updates…") }
    var offer by remember { mutableStateOf<UpdatePolicy.Offer?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableIntStateOf(-1) }
    var working by remember { mutableStateOf(true) }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val ready = apk
        if (ready != null && updater.canRequestInstalls()) {
            context.startActivity(updater.installIntent(ready))
        } else if (ready != null) {
            status = "Allow installs from Resound, then tap Install again."
        }
    }

    fun install(file: File) {
        if (updater.canRequestInstalls()) {
            context.startActivity(updater.installIntent(file))
        } else {
            unknownSourcesLauncher.launch(updater.unknownSourcesIntent())
        }
    }

    // Closing the dialog abandons any staged download, including one whose install was cancelled.
    DisposableEffect(Unit) { onDispose { updater.discardStaged() } }

    LaunchedEffect(Unit) {
        when (val result = updater.check()) {
            is AppUpdater.CheckResult.Available -> {
                offer = result.offer
                status = "Resound ${result.offer.manifest.version} is available."
            }
            is AppUpdater.CheckResult.Current -> status = "You have the latest version (${result.versionName})."
            is AppUpdater.CheckResult.NotPublished -> status = result.message
            is AppUpdater.CheckResult.Failed -> status = result.message
        }
        working = false
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Resound updater") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status)
                offer?.manifest?.mandatoryReason?.let { Text("Required update: $it") }
                if (progress >= 0) LinearProgressIndicator(progress = { progress / 100f })
            }
        },
        confirmButton = {
            when {
                apk != null -> TextButton(onClick = { install(apk!!) }) { Text("Install") }
                offer != null -> TextButton(
                    enabled = !working,
                    onClick = {
                        val selected = offer ?: return@TextButton
                        scope.launch {
                            working = true
                            progress = 0
                            status = "Downloading Resound ${selected.manifest.version}…"
                            when (val result = updater.download(selected) { value -> mainHandler.post { progress = value } }) {
                                is AppUpdater.DownloadResult.Ready -> {
                                    apk = result.apk
                                    status = "Download verified. Ready to install."
                                }
                                is AppUpdater.DownloadResult.Failed -> status = result.message
                            }
                            working = false
                        }
                    },
                ) { Text("Download") }
                else -> TextButton(enabled = false, onClick = {}) { Text("Check") }
            }
        },
        dismissButton = {
            TextButton(enabled = !working, onClick = onDismiss) { Text("Close") }
        },
    )
}
