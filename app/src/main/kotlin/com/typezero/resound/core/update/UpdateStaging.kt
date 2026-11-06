package com.typezero.resound.core.update

import java.io.File

/**
 * Update downloads are staged only in cache/updates. A staged file is abandoned once the update
 * dialog closes, a download fails or the process restarts (a successful install restarts it),
 * so it is deleted at each of those points.
 */
object UpdateStaging {
    fun dir(cacheDir: File): File = File(cacheDir, "updates")

    /** Deletes the staged files; returns how many were removed. */
    fun clear(dir: File): Int = (dir.listFiles() ?: emptyArray()).count { it.isFile && it.delete() }
}
