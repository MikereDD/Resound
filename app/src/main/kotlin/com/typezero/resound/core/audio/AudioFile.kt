/*
 * file:    AudioFile.kt
 * author:  Typezer∅
 * version: 0.1.0
 * desc:    Lightweight handle to a source audio (or video) file plus the
 *          metadata the editor needs. Populated from MediaStore / SAF.
 */
package com.typezero.resound.core.audio

import android.net.Uri

data class AudioFile(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val mimeType: String,
) {
    val isStereo: Boolean get() = channels >= 2
}
