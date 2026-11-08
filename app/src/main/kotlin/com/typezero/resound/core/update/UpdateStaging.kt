package com.typezero.resound.core.update

import java.io.File
import java.util.UUID

/**
 * Update downloads are staged only in cache/updates, each in its own session directory, so a
 * stale or cancelled download can never delete, overwrite or verify another download's files.
 * A session is abandoned once the update dialog closes, its download fails or is cancelled, or
 * the process restarts (a successful install restarts it), so it is deleted at each point.
 */
object UpdateStaging {
    fun dir(cacheDir: File): File = File(cacheDir, "updates")

    /** Deletes everything staged under [dir], including session directories; returns how many entries were removed. */
    fun clear(dir: File): Int = (dir.listFiles() ?: emptyArray()).count { it.deleteRecursively() }

    /** A new, empty directory owned by exactly one download. */
    fun newSession(dir: File): File {
        dir.mkdirs()
        val session = File(dir, "download-${UUID.randomUUID()}")
        check(session.mkdir()) { "Could not create the update staging directory" }
        return session
    }

    /** Deletes one download's session and nothing else. */
    fun discard(session: File) {
        session.deleteRecursively()
    }
}
