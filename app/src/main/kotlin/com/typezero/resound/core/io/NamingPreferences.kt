package com.typezero.resound.core.io

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class NamingPatterns(val recording: String = "", val exports: String = "")

/** Immediate memory choices; lazy IO initialization and serialized latest-snapshot persistence. */
internal class NamingPreferenceState(
    private val scope: CoroutineScope,
    private val read: () -> NamingPatterns,
    private val write: (NamingPatterns) -> Unit,
) {
    private val monitor = Any()
    private val io = Mutex()
    private val mutable = MutableStateFlow(NamingPatterns())
    val state = mutable.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private var touchedRecording = false
    private var touchedExports = false
    private var loaded = false // IO mutex owns initialization.
    private var lastWrite: Job? = null

    private fun load(): Boolean {
        if (loaded) return true
        return try {
            val saved = read()
            synchronized(monitor) {
                mutable.value = NamingPatterns(
                    if (touchedRecording) mutable.value.recording else saved.recording,
                    if (touchedExports) mutable.value.exports else saved.exports)
            }
            loaded = true
            mutableNotice.value = null
            true
        } catch (_: Exception) {
            mutableNotice.value = "Naming preferences unavailable. Current choices work for this session; persistence will be retried."
            false
        }
    }

    suspend fun initialize() = withContext(Dispatchers.IO) { io.withLock { load(); Unit } }

    fun update(recording: String? = null, exports: String? = null) = synchronized(monitor) {
        if (recording != null) touchedRecording = true
        if (exports != null) touchedExports = true
        mutable.value = mutable.value.copy(recording = recording ?: mutable.value.recording,
            exports = exports ?: mutable.value.exports)
        lastWrite = scope.launch(Dispatchers.IO) {
            io.withLock {
                if (load()) {
                    try {
                        // Snapshot AFTER acquiring IO serialization; older jobs cannot write stale values.
                        write(synchronized(monitor) { mutable.value })
                        mutableNotice.value = null
                    } catch (_: Exception) {
                        mutableNotice.value = "Naming preference could not be saved. Current choice works for this session. Edit again to retry."
                    }
                }
            }
        }
    }

    internal suspend fun flush() { synchronized(monitor) { lastWrite }?.join() }
}

internal object NamingPreferences {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var instance: NamingPreferenceState? = null
    fun forContext(context: Context): NamingPreferenceState = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            fun prefs() = app.getSharedPreferences("initial-publication-naming", Context.MODE_PRIVATE)
            NamingPreferenceState(scope,
                read = { NamingPatterns(prefs().getString("recording", "") ?: "", prefs().getString("exports", "") ?: "") },
                write = { check(prefs().edit().putString("recording", it.recording)
                    .putString("exports", it.exports).commit()) { "Preference write failed" } },
            ).also { instance = it }
        }
    }
}
