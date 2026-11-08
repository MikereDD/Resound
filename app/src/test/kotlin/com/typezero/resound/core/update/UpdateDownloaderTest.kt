package com.typezero.resound.core.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * The production download loop (connector, redirects, bounded copy, staging, detached-signature
 * verification) against a loopback HTTP server. Stalled responses keep the client blocked in a
 * real socket read, so cancellation must abort the connection rather than rely on interrupts.
 */
class UpdateDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val anchors = TestKeys.anchors()
    private val apkBytes = Random(23).nextBytes(400_000)
    private val sigBytes = TestKeys.sign(TestKeys.approved, apkBytes)
    private val server = LoopbackServer()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connector = UpdateDownloader.trustedConnector("Resound-test") { url ->
        require(url.startsWith("http://127.0.0.1:${server.port}/")) { "Untrusted test URL: $url" }
    }
    private val root by lazy { UpdateStaging.dir(tmp.newFolder("cache")) }

    @After fun tearDown() {
        server.close()
        background.cancel()
    }

    private fun asset(apkPath: String, sigPath: String = "/r.sig", apk: ByteArray = apkBytes, sig: ByteArray = sigBytes) =
        ManifestFixtures.parse(
            ManifestFixtures.manifest(
                apkSha256 = TestKeys.sha256(apk), apkSize = apk.size.toLong(),
                sigSha256 = TestKeys.sha256(sig), sigSize = sig.size.toLong(),
                publicKeySha256 = anchors.releasePublicKeySha256,
            ),
        ).assets.single().let { it.copy(downloadUrl = server.url(apkPath), signature = it.signature.copy(downloadUrl = server.url(sigPath))) }

    private suspend fun download(
        asset: ReleaseManifest.Asset,
        sessions: MutableList<File> = mutableListOf(),
        onProgress: (Int) -> Unit = {},
        verifyArchive: (File) -> Unit = {},
    ): File = UpdateDownloader.downloadVerified(root, asset, anchors, connector, { sessions += it }, onProgress, verifyArchive)

    private fun stagedFiles(): List<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toList()

    /** An abandoned transfer removes its session when it ends (at once on Android, where open files can be unlinked). */
    private fun awaitDeleted(session: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (session.exists() && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse("session still staged: $session", session.exists())
    }

    @Test(timeout = 60_000)
    fun verifiedDownloadThroughARedirectIsRetainedInItsOwnSession() = runBlocking {
        server.serve("/r.sig", sigBytes)
        server.serve("/r.apk", apkBytes)
        server.redirect("/go.apk", "/r.apk")
        val sessions = mutableListOf<File>()
        val progress = CopyOnWriteArrayList<Int>()

        val apk = download(asset("/go.apk"), sessions, { progress += it })

        val session = sessions.single()
        assertEquals(root, session.parentFile)
        assertEquals(session, apk.parentFile)
        assertArrayEquals(apkBytes, apk.readBytes())
        assertEquals(setOf(apk.name, "${apk.name}.sig"), session.list()!!.toSet())
        assertEquals(100, progress.last())
    }

    @Test(timeout = 60_000)
    fun cancellationAbortsABlockedNetworkReadAndDeletesTheSession() = runBlocking {
        server.serve("/r.sig", sigBytes)
        val streaming = server.stall("/r.apk", apkBytes, prefix = 65_536)
        val sessions = CopyOnWriteArrayList<File>()
        val progressed = CountDownLatch(1)
        val progress = CopyOnWriteArrayList<Int>()
        val result = AtomicReference<File?>()

        val job = background.launch {
            result.set(download(asset("/r.apk"), sessions, { progress += it; if (it > 0) progressed.countDown() }))
        }
        assertTrue(streaming.await(10, TimeUnit.SECONDS))
        assertTrue(progressed.await(10, TimeUnit.SECONDS))
        Thread.sleep(200) // the client is now parked in a socket read the server will not satisfy

        job.cancel()
        val finished = withTimeoutOrNull(5_000) { job.join(); true }
        assertEquals("cancellation must not wait for the blocked read", true, finished)
        val writesAtCancel = progress.size

        server.release() // the rest of the body arrives: the abandoned transfer must not write it
        awaitDeleted(sessions.single())

        assertNull(result.get())
        assertEquals("no write after cancellation", writesAtCancel, progress.size)
        assertEquals(emptyList<String>(), stagedFiles())
    }

    @Test(timeout = 60_000)
    fun cancellationWhileVerifyingDeletesTheSessionAndHandsNothingOff() = runBlocking {
        server.serve("/r.sig", sigBytes)
        server.serve("/r.apk", apkBytes)
        val sessions = CopyOnWriteArrayList<File>()
        val verifying = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val result = AtomicReference<File?>()

        val job = background.launch {
            result.set(download(asset("/r.apk"), sessions) { verifying.countDown(); proceed.await(10, TimeUnit.SECONDS) })
        }
        assertTrue(verifying.await(10, TimeUnit.SECONDS))
        job.cancel()
        assertEquals(true, withTimeoutOrNull(5_000) { job.join(); true })
        proceed.countDown() // verification then finishes in the abandoned transfer

        awaitDeleted(sessions.single())
        assertNull(result.get())
        assertEquals(emptyList<String>(), stagedFiles())
    }

    @Test(timeout = 60_000)
    fun aStaleDownloadCannotTouchANewerVerifiedDownload() = runBlocking {
        server.serve("/r.sig", sigBytes)
        server.serve("/fresh.apk", apkBytes)
        val staleStreaming = server.stall("/stale.apk", apkBytes, prefix = 65_536, truncateAfterRelease = true)
        val staleSessions = CopyOnWriteArrayList<File>()
        val staleOutcome = AtomicReference<Result<File>?>()

        val stale = background.launch { staleOutcome.set(runCatching { download(asset("/stale.apk"), staleSessions) }) }
        assertTrue(staleStreaming.await(10, TimeUnit.SECONDS))

        val freshSessions = mutableListOf<File>()
        val fresh = download(asset("/fresh.apk"), freshSessions)
        assertArrayEquals(apkBytes, fresh.readBytes())

        server.release() // the stale transfer now ends early and must fail on its own files only
        assertEquals(true, withTimeoutOrNull(10_000) { stale.join(); true })

        assertTrue("stale download must fail", staleOutcome.get()!!.isFailure)
        assertFalse(staleSessions.single().exists())
        assertTrue(freshSessions.single() != staleSessions.single())
        assertArrayEquals(apkBytes, fresh.readBytes())
        assertEquals(TestKeys.sha256(apkBytes), ReleaseVerifier.sha256(fresh))
        assertEquals(setOf(fresh.name, "${fresh.name}.sig"), freshSessions.single().list()!!.toSet())
    }

    @Test(timeout = 60_000)
    fun failedVerificationDeletesTheSession() = runBlocking {
        server.serve("/r.sig", sigBytes)
        val tampered = apkBytes.copyOf().also { it[1234] = (it[1234] + 1).toByte() }
        server.serve("/r.apk", tampered)
        val sessions = mutableListOf<File>()
        try {
            download(asset("/r.apk"), sessions)
            fail("tampered APK accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("SHA-256"))
        }
        assertFalse(sessions.single().exists())
        assertEquals(emptyList<String>(), stagedFiles())
    }

    @Test(timeout = 60_000)
    fun archiveIdentityFailureDeletesTheSession() = runBlocking {
        server.serve("/r.sig", sigBytes)
        server.serve("/r.apk", apkBytes)
        val sessions = mutableListOf<File>()
        try {
            download(asset("/r.apk"), sessions) { throw IllegalArgumentException("Downloaded APK package name does not match Resound") }
            fail("identity failure ignored")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("package name"))
        }
        assertFalse(sessions.single().exists())
    }

    @Test(timeout = 60_000)
    fun oversizedResponseIsRejectedWhileStreaming() = runBlocking {
        server.serve("/r.sig", sigBytes)
        server.serve("/r.apk", apkBytes + ByteArray(1))
        val sessions = mutableListOf<File>()
        try {
            download(asset("/r.apk"), sessions)
            fail("oversized response accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("larger than"))
        }
        assertFalse(sessions.single().exists())
    }

    @Test(timeout = 60_000)
    fun redirectsAreCheckedAtEveryHop() = runBlocking {
        server.serve("/r.sig", sigBytes)
        server.redirect("/go.apk", "http://localhost:${server.port}/r.apk")
        val sessions = mutableListOf<File>()
        try {
            download(asset("/go.apk"), sessions)
            fail("redirect to an unapproved origin followed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("Untrusted test URL"))
        }
        assertFalse(sessions.single().exists())
    }

    @Test fun productionConnectorRefusesNonHttpsBeforeConnecting() {
        try {
            UpdateDownloader.trustedConnector("Resound-test").open(server.url("/r.apk"), TransferGuard())
            fail("plain HTTP accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("HTTPS"))
        }
        assertEquals(0, server.requests.size)
    }

    @Test(timeout = 60_000)
    fun manifestFetchIsBoundedReportsStatusAndIsCancellable() = runBlocking {
        server.serve("/m.json", ByteArray(100))
        assertEquals(100, UpdateDownloader.fetchBytes(connector, server.url("/m.json"), 100).size)
        try {
            UpdateDownloader.fetchBytes(connector, server.url("/m.json"), 99)
            fail("oversized manifest accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("larger than"))
        }
        try {
            UpdateDownloader.fetchBytes(connector, server.url("/missing.json"), 100)
            fail("404 accepted")
        } catch (e: UpdateDownloader.HttpStatusException) {
            assertEquals(404, e.code)
        }

        val streaming = server.stall("/slow.json", ByteArray(5_000), prefix = 10)
        val job = background.launch { UpdateDownloader.fetchBytes(connector, server.url("/slow.json"), 5_000) }
        assertTrue(streaming.await(10, TimeUnit.SECONDS))
        Thread.sleep(200)
        job.cancel()
        assertEquals("cancellation must abort the manifest read", true, withTimeoutOrNull(5_000) { job.join(); true })
    }

    /**
     * Minimal loopback HTTP/1.1 server on a real socket (android.jar has no JDK HTTP server).
     * Stalled routes hold the response open until [release].
     */
    private class LoopbackServer : AutoCloseable {
        private val routes = ConcurrentHashMap<String, (OutputStream) -> Unit>()
        private val released = CountDownLatch(1)
        private val executor = Executors.newCachedThreadPool()
        private val clients = CopyOnWriteArrayList<Socket>()
        private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val requests = CopyOnWriteArrayList<String>()
        val port: Int get() = listener.localPort

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val client = try { listener.accept() } catch (_: IOException) { break }
                    clients += client
                    executor.execute { handle(client) }
                }
            }
        }

        private fun handle(client: Socket) {
            client.use { handleOpen(it) }
        }

        private fun handleOpen(client: Socket) {
            try {
                val input = client.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val b = input.read()
                    if (b < 0) return
                    head.append(b.toChar())
                }
                val path = head.lineSequence().first().split(' ')[1]
                requests += path
                val out = client.getOutputStream()
                val route = routes[path]
                if (route == null) out.write(header(404, 0)) else route(out)
                out.flush()
            } catch (_: IOException) {
                // The client aborted the transfer.
            }
        }

        fun url(path: String) = "http://127.0.0.1:$port$path"

        fun serve(path: String, body: ByteArray) {
            routes[path] = { it.write(header(200, body.size.toLong())); it.write(body) }
        }

        fun redirect(path: String, location: String) {
            routes[path] = { it.write(header(302, 0, "Location: $location\r\n")) }
        }

        /** Sends [prefix] bytes of [body], then holds; after [release] it completes or truncates. */
        fun stall(path: String, body: ByteArray, prefix: Int, truncateAfterRelease: Boolean = false): CountDownLatch {
            val streaming = CountDownLatch(1)
            routes[path] = { out ->
                out.write(header(200, body.size.toLong()))
                out.write(body, 0, prefix)
                out.flush()
                streaming.countDown()
                released.await(20, TimeUnit.SECONDS)
                if (truncateAfterRelease) out.write(body, prefix, 1024) else out.write(body, prefix, body.size - prefix)
            }
            return streaming
        }

        fun release() = released.countDown()

        private fun header(code: Int, length: Long, extra: String = "") =
            "HTTP/1.1 $code X\r\nContent-Length: $length\r\nConnection: close\r\n$extra\r\n".toByteArray(Charsets.ISO_8859_1)

        override fun close() {
            released.countDown()
            listener.close()
            clients.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }
}
