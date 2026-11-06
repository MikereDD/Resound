package com.typezero.resound.feature.edit

import com.typezero.resound.feature.record.RecordingRecoveryAdmission
import java.io.File

internal enum class RecoveryReadOutcome { NO_USABLE_MEDIA, UNVERIFIED, CLEANUP_UNCERTAIN }

/** The Editor's ON_STOP pause-error boundary; cancellation still escapes to the caller. */
internal fun pausePlaybackForBackground(
    pause: () -> Unit,
    onPlaybackUnavailable: () -> Unit,
    recoveryActions: RecoveryActions?,
) {
    try { pause() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) {
        onPlaybackUnavailable()
        recoveryActions?.playbackUnavailable()
    }
}

/** Editor recovery action boundary. Caller holds the store's actual action lease. */
internal class RecoveryActions(
    private val check: suspend (File, RecordingRecoveryAdmission.Lease) -> RecoveryReadOutcome,
    private val onHealth: (RecoveryHealth) -> Unit,
) {
    /** Called by the Editor's readiness poll and transport-error boundary for this source. */
    fun playbackUnavailable() = onHealth(RecoveryHealth.UNVERIFIED)

    suspend fun save(file: File, lease: RecordingRecoveryAdmission.Lease,
                     publishOriginal: suspend () -> String?): String {
        onHealth(RecoveryHealth.UNVERIFIED) // Revoke old last-check evidence even if read delivery is cancelled.
        val checked = check(file, lease)
        onHealth(if (checked == RecoveryReadOutcome.NO_USABLE_MEDIA) RecoveryHealth.DAMAGED else RecoveryHealth.UNVERIFIED)
        if (checked == RecoveryReadOutcome.CLEANUP_UNCERTAIN) return "Draft retained and protected: media read cleanup is uncertain."
        val nonempty = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file.length() > 0L }
        if (!nonempty) return "No original bytes available to save. Draft kept."
        val warning = if (checked == RecoveryReadOutcome.NO_USABLE_MEDIA) "playback unavailable" else "playback unverified"
        val destination = publishOriginal()
        return if (destination == null) "Already saved to the library; $warning. Draft kept."
        else "Original copy saved; $warning. Saved to $destination. Draft kept."
    }

    suspend fun open(file: File, lease: RecordingRecoveryAdmission.Lease,
                     preview: suspend () -> PreviewLoad.Result?): String {
        onHealth(RecoveryHealth.UNVERIFIED) // Revoke old last-check evidence even if read delivery is cancelled.
        val checked = check(file, lease)
        val health = if (checked == RecoveryReadOutcome.NO_USABLE_MEDIA) RecoveryHealth.DAMAGED else RecoveryHealth.UNVERIFIED
        onHealth(health)
        if (checked == RecoveryReadOutcome.NO_USABLE_MEDIA) return "Appears damaged or incomplete. Draft kept; Save can preserve an original copy."
        if (checked == RecoveryReadOutcome.CLEANUP_UNCERTAIN) return "Draft retained and protected: media read cleanup is uncertain."
        val result = preview()
        onHealth(RecoveryHealth.fromPreview(result))
        return if (result?.ready != true) result?.message ?: "Playback unavailable; draft kept."
        else result.message ?: "Loaded draft ${file.name}"
    }
}
