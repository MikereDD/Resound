/*
 * file:    Ringtones.kt
 * author:  Mike Redd (typezero)
 * version: 0.4.0
 * desc:    Sets an audio file as the default ringtone. Inserts a copy into the
 *          MediaStore Ringtones collection (IS_RINGTONE=1), then points the
 *          system default at it. Requires WRITE_SETTINGS (a special permission
 *          granted via a system screen, not a runtime dialog).
 */
package com.typezero.resound.core.io

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import java.io.File

object Ringtones {

    sealed interface Result {
        data object NeedsPermission : Result
        data class Ok(val name: String) : Result
        data class Error(val message: String) : Result
    }

    /** True if the app may write system settings (needed to set a ringtone). */
    fun canWrite(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)

    /** Intent that opens the system screen to grant WRITE_SETTINGS. */
    fun manageWriteSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * Copy [source] into the Ringtones collection and set it as the default
     * ringtone. [source] is a local file (already decoded to cache).
     */
    fun setAsDefault(context: Context, source: File, displayName: String, ext: String): Result {
        if (com.typezero.resound.BuildConfig.IS_DEVTEST) return Result.Error("Disabled in DEV TEST build (would change the device-wide ringtone)")
        if (!canWrite(context)) return Result.NeedsPermission
        return try {
            val resolver = context.contentResolver
            val mime = Outputs.mimeFor(ext)

            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_RINGTONES}/${Outputs.SUBDIR}")
                    put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                    put(MediaStore.Audio.Media.IS_MUSIC, 0)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val u = resolver.insert(collection, values)
                    ?: return Result.Error("MediaStore insert failed")
                resolver.openOutputStream(u).use { out ->
                    requireNotNull(out) { "Could not open output stream" }
                    source.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(u, values, null, null)
                u
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_RINGTONES),
                    Outputs.SUBDIR,
                )
                dir.mkdirs()
                val dest = File(dir, displayName)
                source.copyTo(dest, overwrite = true)
                val values = ContentValues().apply {
                    @Suppress("DEPRECATION")
                    put(MediaStore.MediaColumns.DATA, dest.absolutePath)
                    put(MediaStore.MediaColumns.TITLE, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.IS_RINGTONE, true)
                    put(MediaStore.Audio.Media.IS_MUSIC, false)
                }
                @Suppress("DEPRECATION")
                resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return Result.Error("MediaStore insert failed")
            }

            RingtoneManager.setActualDefaultRingtoneUri(
                context,
                RingtoneManager.TYPE_RINGTONE,
                uri,
            )
            Result.Ok(displayName)
        } catch (t: Throwable) {
            Result.Error(t.message ?: "failed")
        }
    }
}
