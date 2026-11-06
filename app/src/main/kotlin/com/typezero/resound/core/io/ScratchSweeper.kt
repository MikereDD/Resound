/*
 * file:    ScratchSweeper.kt
 * desc:    Recovery of scratch abandoned by a PREVIOUS process (src_ files in cache and files in
 *          cache/out). Age is NOT ownership, so it is not used. Safety rests on a startup contract:
 *          the app is single-process (manifest declares no android:process, no services/receivers),
 *          and the sweep runs synchronously in Application.onCreate before the container, any
 *          Activity or any native operation exists. The sweep REFUSES (returns -1) if any
 *          OperationFiles was already created in this process or if it already ran, so a later
 *          misuse cannot delete live inputs. Recording drafts live in filesDir and are never touched.
 *          Cost: a directory listing on the main thread at startup (a few files).
 */
package com.typezero.resound.core.io

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object ScratchSweeper {
    private val operationSeen = AtomicBoolean(false)
    private val ran = AtomicBoolean(false)

    internal fun noteOperationStarted() { operationSeen.set(true) }
    internal fun resetForTest() { operationSeen.set(false); ran.set(false) }

    /** Returns files deleted, or -1 if refused because the startup window is closed. */
    fun sweepAtStartup(cacheDir: File): Int {
        if (operationSeen.get() || !ran.compareAndSet(false, true)) return -1
        var n = 0
        (cacheDir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.startsWith("src_") }
            .forEach { if (it.delete()) n++ }
        File(cacheDir, "out").listFiles()?.filter { it.isFile }?.forEach { if (it.delete()) n++ }
        return n
    }
}
