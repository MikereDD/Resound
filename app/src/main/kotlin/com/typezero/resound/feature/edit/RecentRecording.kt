package com.typezero.resound.feature.edit

import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A reference to the verified published copy, never audio ownership or a recovery receipt. */
data class RecentRecording(
    val uri: String,
    val name: String,
    val completedAtMs: Long,
    val durationMs: Long? = null,
    val format: String? = null,
    val channels: Int? = null,
)

/** Blocking reference IO; controller callers dispatch off Main. No audio is copied. */
class RecentRecordingStore(private val fileProvider: () -> File) {
    constructor(file: File) : this({ file })
    private val file: File by lazy(fileProvider)
    private fun validate(record: RecentRecording) {
        require(record.uri.length in 1..4096 && record.uri.none { it.isISOControl() })
        val uri = try { java.net.URI(record.uri) } catch (t: Exception) {
            throw IllegalArgumentException("Invalid recent recording URI", t)
        }
        require(!uri.isOpaque && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawUserInfo == null && uri.port == -1)
        require(when (uri.scheme) {
            "content" -> uri.rawAuthority == "media" &&
                uri.path.orEmpty().matches(Regex("/[A-Za-z0-9_-]+/audio/media/[1-9][0-9]*")) &&
                uri.path.substringAfterLast('/').toLongOrNull() != null
            "file" -> uri.rawAuthority.isNullOrEmpty() && File(uri).isAbsolute &&
                !File(uri).canonicalFile.toPath().startsWith(requireNotNull(file.parentFile).canonicalFile.toPath())
            else -> false
        }) { "Recent must reference a public recording" }
        require(record.name.length in 1..256 && record.name.isNotBlank() &&
            record.name.none { it.isISOControl() || it == '/' || it == '\\' })
        require(record.completedAtMs in 1..253402300799999L)
        require(record.durationMs == null || record.durationMs in 1..31536000000L)
        require(record.channels == null || record.channels in 1..32)
        require(record.format == null || (record.format.length in 1..64 &&
            record.format.isNotBlank() && record.format.none { it.isISOControl() }))
    }

    fun read(): RecentRecording? { return try {
        if (file.length() !in 1..8192L) return null
        val bytes = ByteArray(8193)
        val count = file.inputStream().use { input ->
            var n = 0
            while (n < bytes.size) {
                val read = input.read(bytes, n, bytes.size - n)
                if (read < 0) break
                n += read
            }
            n
        }
        if (count > 8192) return null
        DataInputStream(java.io.ByteArrayInputStream(bytes, 0, count)).use { input ->
            if (input.readInt() != 1) return null
            val uri = input.readUTF()
            val name = input.readUTF()
            val completed = input.readLong()
            val duration = input.readLong()
            val format = input.readUTF()
            val channels = input.readInt()
            require(duration >= 0 && channels >= 0 && input.read() == -1)
            RecentRecording(uri, name, completed, duration.takeIf { it > 0 },
                format.ifEmpty { null }, channels.takeIf { it > 0 }).also(::validate)
        }
    } catch (c: CancellationException) { throw c } catch (_: Exception) { null } }

    /** Delete this app-owned reference only; never resolve or delete its media URI. */
    fun clear(beforeCommit: () -> Unit = {}) {
        check(!file.isDirectory) { "Recent reference is not a file" }
        beforeCommit()
        Files.deleteIfExists(file.toPath())
    }

    fun write(record: RecentRecording, beforeCommit: () -> Unit = {}) {
        validate(record)
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Could not create recent recording storage" }
        val temp = File.createTempFile("recent-", ".tmp", parent)
        try {
            DataOutputStream(temp.outputStream()).use { output ->
                output.writeInt(1)
                output.writeUTF(record.uri)
                output.writeUTF(record.name)
                output.writeLong(record.completedAtMs)
                output.writeLong(record.durationMs ?: 0L)
                output.writeUTF(record.format.orEmpty())
                output.writeInt(record.channels ?: 0)
            }
            require(temp.length() in 1..8192L) { "Recent recording reference is too large" }
            beforeCommit()
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}

/** Sticky, reference-specific card feedback; not a media-health/playability assertion. */
data class RecentRecordingNotice(val recording: RecentRecording, val clearFailed: Boolean = false)

/** One application-owned controller serializes initialization and reference replacement. */
class RecentRecordingController(
    private val store: RecentRecordingStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var initialized = false
    private var publicationSequence = 0L
    private val mutableState = MutableStateFlow<RecentRecording?>(null)
    val state = mutableState.asStateFlow()
    val current: RecentRecording? get() = state.value
    private val mutableNotice = MutableStateFlow<RecentRecordingNotice?>(null)
    val notice = mutableNotice.asStateFlow()

    /** Keep failure feedback beside the saved reference, independent of transient Editor status. */
    suspend fun reopen(expected: RecentRecording, load: suspend () -> PreviewLoad.Result?,
                       onUnavailable: suspend () -> Unit): String {
        if (!mutex.withLock { current == expected }) return "Recent recording changed. Try again."
        var unavailable = false
        val message = reopenRecentRecording(load) {
            unavailable = true
            mutex.withLock {
                if (current == expected) mutableNotice.value = RecentRecordingNotice(expected)
            }
            onUnavailable()
        }
        if (!unavailable) mutex.withLock {
            if (current == expected) mutableNotice.value = null
        }
        return message
    }

    /** Identity-checked, serialized reference removal. In-flight older metadata cannot resurrect it. */
    suspend fun clear(expected: RecentRecording): Boolean = mutex.withLock {
        if (current != expected) return@withLock false
        withContext(io) {
            val job = currentCoroutineContext()
            job.ensureActive()
            try {
                store.clear { job.ensureActive() }
            } catch (c: CancellationException) { throw c }
              catch (_: Exception) {
                job.ensureActive()
                mutableNotice.value = RecentRecordingNotice(expected, clearFailed = true)
                return@withContext false
            }
            // Commit memory here: cancellation at dispatcher return must not resurrect the card.
            ++publicationSequence
            mutableState.value = null
            mutableNotice.value = null
            initialized = true
            true
        }
    }

    suspend fun initialize() = mutex.withLock {
        if (!initialized) withContext(io) {
            val saved = store.read()
            currentCoroutineContext().ensureActive()
            mutableState.value = saved
            initialized = true
        }
    }

    /** Only call from normal Stop. Thrown/cancelled publication never reaches reference IO. */
    suspend fun <T> published(publish: suspend () -> T,
                              onReferenceFailure: (Exception) -> Unit = { throw it },
                              describe: suspend (T) -> RecentRecording): T {
        val publication = publish()
        currentCoroutineContext().ensureActive()
        // Claim completion order before metadata can suspend. Wall clocks can repeat or move
        // backwards, and an older metadata read must not displace a newer published Stop.
        val sequence = mutex.withLock { ++publicationSequence }
        try {
            val record = describe(publication)
            currentCoroutineContext().ensureActive()
            mutex.withLock {
                withContext(io) {
                    val job = currentCoroutineContext()
                    job.ensureActive()
                    if (!initialized) {
                        val saved = store.read()
                        job.ensureActive()
                        mutableState.value = saved
                        initialized = true
                    }
                    if (sequence == publicationSequence) {
                        store.write(record) { job.ensureActive() }
                        // State changes at the same commit boundary as disk, not dispatcher return.
                        mutableState.value = record
                        mutableNotice.value = null
                    }
                }
            }
        } catch (c: CancellationException) { throw c }
          catch (failed: Exception) {
            currentCoroutineContext().ensureActive()
            onReferenceFailure(failed)
        }
        return publication
    }
}
