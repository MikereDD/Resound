/*
 * file:    OperationFiles.kt
 * desc:    Files owned by ONE operation, with explicit lifecycle:
 *            OPEN      registrations and native starts accepted
 *            DRAINING  cleanup requested but native work still active: registrations accepted
 *                      (retained), deletion deferred until the last CONFIRMED native terminal
 *            CLEANING  deletion scheduled exactly once; irrevocable. add() returns false,
 *                      nativeStarted() returns false
 *            DONE      deletion finished (see failedToDelete for survivors)
 *          A timeout is never terminal: with no confirmation the files stay quarantined.
 *          Rejected registrations never leave a silent orphan: add() reports it; adopt() deletes
 *          the caller's freshly created, not-yet-shared file and throws.
 */
package com.typezero.resound.core.io

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class OperationFiles(private val executor: Executor = defaultExecutor) {
    private enum class State { OPEN, DRAINING, CLEANING, DONE }

    private val lock = Any()
    private val files = ArrayList<File>()
    private val failed = ArrayList<File>()
    private var state = State.OPEN
    private var nativeActive = 0

    init { ScratchSweeper.noteOperationStarted() }

    /** Register an owned file. False if cleanup is already irrevocable (caller still owns the file). */
    fun add(file: File): Boolean = synchronized(lock) {
        if (state == State.CLEANING || state == State.DONE) false else { files.add(file); true }
    }

    /**
     * Register a file the caller JUST created and has not shared with anyone. If rejected, deletes it
     * (safe: no other consumer can hold it) and throws, so no orphan results.
     */
    fun adopt(file: File): File {
        if (!add(file)) {
            runCatching { file.delete() }
            throw IllegalStateException("Operation already cleaned up; file not adopted")
        }
        return file
    }

    operator fun plusAssign(file: File) { adopt(file) }

    val size: Int get() = synchronized(lock) { files.size }
    val failedToDelete: List<File> get() = synchronized(lock) { failed.toList() }
    /** Cleanup requested but native code may still be using the files. */
    val isQuarantined: Boolean get() = synchronized(lock) { state == State.DRAINING && nativeActive > 0 }
    /** Deletion finished and every file is gone. */
    val isCleaned: Boolean get() = synchronized(lock) { state == State.DONE && failed.isEmpty() }
    /** Deletion scheduled or finished. */
    val isClosed: Boolean get() = synchronized(lock) { state == State.CLEANING || state == State.DONE }

    /** Call BEFORE submitting native work. False once cleanup is irrevocable. */
    fun nativeStarted(): Boolean = synchronized(lock) {
        if (state == State.CLEANING || state == State.DONE) false else { nativeActive++; true }
    }

    /** Confirmed native terminal state (or confirmed failure to submit). Extra calls are ignored. */
    fun nativeTerminal() {
        val schedule = synchronized(lock) {
            if (nativeActive > 0) nativeActive--
            if (nativeActive == 0 && state == State.DRAINING) { state = State.CLEANING; true } else false
        }
        if (schedule) executor.execute { deleteNow() }
    }

    /** Caller finished. Deletes now, or defers until native terminal. Repeat calls are no-ops. */
    fun deleteAll() {
        val schedule = synchronized(lock) {
            when (state) {
                State.OPEN -> if (nativeActive == 0) { state = State.CLEANING; true } else { state = State.DRAINING; false }
                else -> false
            }
        }
        if (schedule) executor.execute { deleteNow() }
    }

    private fun deleteNow() {
        val victims = synchronized(lock) { files.toList().also { files.clear() } }   // state is CLEANING: no new adds
        com.typezero.resound.core.DevTrace.e("ownership delete-executing files=${victims.size}")
        val survivors = victims.filter { f -> !(runCatching { f.delete() }.getOrDefault(false) || !f.exists()) }
        synchronized(lock) { failed.addAll(survivors); state = State.DONE }
    }

    companion object {
        private val defaultExecutor: Executor by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "resound-cleanup").apply { isDaemon = true } }
        }
    }
}
