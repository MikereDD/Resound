package com.typezero.resound.core.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

/**
 * Abort state shared between a cancelled caller and its abandoned blocking transfer. Neither
 * coroutine cancellation nor Thread.interrupt unblocks a socket read, and HttpURLConnection
 * disconnect() is not guaranteed to either (the JDK's waits on the reading thread's stream lock).
 * So the transfer checks [checkActive] after every read before it writes (an abort racing that
 * check lets at most one buffer through, into the already-discarded session), and abort
 * disconnects tracked connections without blocking the caller; the read timeout bounds the rest.
 */
internal class TransferGuard {
    private val lock = Any()
    private var aborted = false
    private val connections = ArrayList<HttpURLConnection>()

    /** Registers [connection] for abort. After an abort it is disconnected at once and cancellation is thrown. */
    fun track(connection: HttpURLConnection): HttpURLConnection {
        val abortNow = synchronized(lock) {
            if (!aborted) connections += connection
            aborted
        }
        if (abortNow) {
            connection.disconnect()
            throw CancellationException("Update download cancelled")
        }
        return connection
    }

    fun checkActive() {
        if (synchronized(lock) { aborted }) throw CancellationException("Update download cancelled")
    }

    /** Marks the transfer aborted; [dispatch] runs the disconnects so the caller never blocks on them. */
    fun abort(dispatch: (() -> Unit) -> Unit) {
        val open = synchronized(lock) {
            aborted = true
            connections.toList().also { connections.clear() }
        }
        if (open.isNotEmpty()) dispatch { open.forEach { runCatching { it.disconnect() } } }
    }
}

/**
 * Android-free update transfer: bounded manifest fetch, and one download per private staging
 * session that is kept only when its own files verify. Android identity checks are passed in.
 */
internal object UpdateDownloader {
    /**
     * Blocking transfers run here, detached from the caller, so cancellation returns at once
     * even while a socket read is blocked. After an abort, work here writes nothing past its next
     * read and ends at that read's result, the disconnect or the read timeout.
     */
    private val transfers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun interface Connector {
        fun open(url: String, guard: TransferGuard): HttpURLConnection
    }

    class HttpStatusException(val code: Int) : IllegalStateException("Server returned HTTP $code")

    /** Trusted HTTPS only (or [requireUrl] in host tests); every redirect hop is checked again. */
    fun trustedConnector(
        userAgent: String,
        requireUrl: (String) -> Unit = { UpdatePolicy.requireTrustedHttps(it) },
    ): Connector = Connector { url, guard ->
        var target = url
        repeat(UpdatePolicy.MAX_REDIRECTS + 1) {
            requireUrl(target)
            val connection = guard.track(URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json, application/octet-stream, application/vnd.android.package-archive")
                setRequestProperty("User-Agent", userAgent)
                connect()
            }
            val code = connection.responseCode
            guard.checkActive()
            when {
                code in 200..299 -> return@Connector connection
                UpdatePolicy.isRedirect(code) -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    target = UpdatePolicy.redirectTarget(target, location, requireUrl)
                }
                else -> {
                    connection.disconnect()
                    throw HttpStatusException(code)
                }
            }
        }
        error("Too many update redirects")
    }

    /** At most [maxBytes] bytes from [url]; a larger response is rejected while streaming. */
    suspend fun fetchBytes(connector: Connector, url: String, maxBytes: Long): ByteArray = transfer { guard ->
        val buffer = ByteArrayOutputStream()
        val connection = connector.open(url, guard)
        try {
            connection.inputStream.use { copyLimited(it, buffer, maxBytes, guard) {} }
        } finally {
            connection.disconnect()
        }
        guard.checkActive()
        buffer.toByteArray()
    }

    /**
     * Downloads the signature and APK of [asset] into a new session under [stagingRoot], verifies
     * sizes, SHA-256 and the pinned detached signature, then [verifyArchive] (Android identity).
     * Returns the verified APK. On any failure or cancellation the session is deleted; no other
     * download's files are ever touched.
     */
    suspend fun downloadVerified(
        stagingRoot: File,
        asset: ReleaseManifest.Asset,
        anchors: TrustAnchors,
        connector: Connector,
        onSession: (File) -> Unit = {},
        onProgress: (Int) -> Unit = {},
        verifyArchive: (File) -> Unit,
    ): File {
        val session = UpdateStaging.newSession(stagingRoot)
        try {
            onSession(session)
            val apk = transfer { guard ->
                try {
                    val signature = staged(session, asset.signature.fileName)
                    val apk = staged(session, asset.fileName)
                    downloadTo(connector, guard, asset.signature.downloadUrl, signature, asset.signature.size) {}
                    downloadTo(connector, guard, asset.downloadUrl, apk, asset.size, onProgress)
                    guard.checkActive()
                    ReleaseVerifier.verify(apk, signature, asset, anchors)
                    verifyArchive(apk)
                    guard.checkActive()
                    apk
                } catch (error: Throwable) {
                    // Also runs when an abandoned transfer ends, after its files are closed.
                    UpdateStaging.discard(session)
                    throw error
                }
            }
            currentCoroutineContext().ensureActive()
            onProgress(100)
            return apk
        } catch (error: Throwable) {
            UpdateStaging.discard(session)
            throw error
        }
    }

    /**
     * Runs blocking transfer work in [transfers] and awaits it. If the caller is cancelled, the
     * await ends immediately, the work is aborted (it cannot write again and its connections are
     * disconnected), and cancellation is rethrown instead of any I/O error.
     */
    private suspend fun <T> transfer(block: (TransferGuard) -> T): T {
        val guard = TransferGuard()
        val work = transfers.async { block(guard) }
        try {
            return work.await()
        } catch (error: Throwable) {
            if (error is CancellationException) guard.abort { disconnect -> transfers.launch { disconnect() } }
            currentCoroutineContext().ensureActive()
            throw error
        }
    }

    private fun staged(session: File, fileName: String): File {
        require(fileName.isNotEmpty() && fileName != "." && fileName != ".." && File(fileName).name == fileName) {
            "Invalid release file name"
        }
        return File(session, fileName)
    }

    private fun downloadTo(
        connector: Connector,
        guard: TransferGuard,
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Int) -> Unit,
    ) {
        val connection = connector.open(url, guard)
        try {
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    copyLimited(input, output, expectedSize, guard) { copied ->
                        onProgress(((copied * 100L) / expectedSize).toInt().coerceIn(0, 100))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long, guard: TransferGuard, onCopied: (Long) -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            guard.checkActive()
            if (read < 0) break
            copied += read
            require(copied <= limit) { "Download is larger than the release metadata allows" }
            output.write(buffer, 0, read)
            onCopied(copied)
        }
    }
}
