/*
 * file:    MainActivity.kt
 * author:  Mike Redd (typezero)
 * version: 0.8.0-dev.6
 * desc:    Premium top-level app shell with persistent bottom navigation.
 */
package com.typezero.resound

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.typezero.resound.di.AppContainer
import com.typezero.resound.feature.edit.EditScreen
import com.typezero.resound.feature.library.LibraryScreen
import com.typezero.resound.feature.settings.SettingsScreen
import com.typezero.resound.feature.timeline.TimelineScreen
import com.typezero.resound.ui.components.ResoundBottomBar
import com.typezero.resound.ui.components.ResoundDestination
import com.typezero.resound.ui.theme.ResoundTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ResoundApp).container
        setContent {
            ResoundTheme {
                ResoundRoot(container)
            }
        }
    }
}

@Composable
private fun ResoundRoot(container: AppContainer) {
    var destinationName by rememberSaveable {
        mutableStateOf(ResoundDestination.Editor.name)
    }
    val destination = ResoundDestination.valueOf(destinationName)
    var editorRequestUri by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        bottomBar = {
            ResoundBottomBar(
                selected = destination,
                onSelect = { destinationName = it.name },
            )
        },
    ) { contentPadding ->
        Box(modifier = Modifier.padding(contentPadding)) {
            when (destination) {
                ResoundDestination.Editor -> EditScreen(
                    waveformExtractor = container.waveformExtractor,
                    ffmpeg = container.ffmpeg,
                    recorder = container.recordingController,
                    playerFactory = container.playerFactory,
                    initialUri = editorRequestUri?.let(Uri::parse),
                )

                ResoundDestination.Multitrack -> TimelineScreen(
                    waveformExtractor = container.waveformExtractor,
                    ffmpeg = container.ffmpeg,
                    session = container.timelineSession,
                    onBack = { destinationName = ResoundDestination.Editor.name },
                )

                ResoundDestination.Library -> LibraryScreen(
                    onOpenInEditor = { uri ->
                        editorRequestUri = uri.toString()
                        destinationName = ResoundDestination.Editor.name
                    },
                )
                ResoundDestination.Settings -> SettingsScreen()
            }
        }
    }
}
