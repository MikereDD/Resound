/*
 * file:    ImportGuard.kt
 * desc:    Validation boundary for Editor imports. NOTE: AudioFiles maps a missing duration to 0,
 *          so durationMs <= 0 means "zero OR unknown"; both are refused here with one message.
 */
package com.typezero.resound.feature.edit

const val EMPTY_AUDIO_MESSAGE = "Load failed: audio has zero or unknown duration."

/** Runs [adopt] (which mutates Editor state / starts playback) only when the duration is positive. */
inline fun <T> adoptIfValid(durationMs: Long, adopt: () -> T): T? =
    if (durationMs > 0L) adopt() else null
