/*
 * file:    LatestOnlySnackbar.kt
 * author:  Typezer∅
 * desc:    Latest-only snackbar announcements. SnackbarHostState queues every showSnackbar call behind a mutex, so
 *          dismissing the visible snackbar lets older queued calls appear after a newer outcome. Each announcement
 *          here cancels the previous one instead: a cancelled showSnackbar leaves the queue, or clears itself if
 *          it is visible, so only the newest message is ever shown.
 */
package com.typezero.resound.feature.timeline

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Call [announce] from one thread (the UI thread). Cancelling [scope] removes any visible or queued message. */
internal class LatestOnlySnackbar(
    private val scope: CoroutineScope,
    val hostState: SnackbarHostState = SnackbarHostState(),
) {
    private var current: Job? = null

    fun announce(message: String) {
        current?.cancel()
        current = scope.launch {
            hostState.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }
}
