package com.typezero.resound.feature.edit

import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecentRecordingTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = File(temporary.root, "recent.ref")
    private fun recording(id: String) = RecentRecording("content://media/external/audio/media/$id", "recording_$id.m4a", 123456789L, 2500L, "M4A", 1)

    @Test fun onlySuccessfulPublicationReplacesPreviousAndSurvivesRestart() = runBlocking {
        val file = file()
        val controller = RecentRecordingController(RecentRecordingStore(file))
        val first = recording("1")
        var described: String? = null
        controller.published(publish = { "verified-uri" }, describe = { described = it; first })
        assertEquals("verified-uri", described)
        assertEquals(first, controller.current)
        val restarted = RecentRecordingController(RecentRecordingStore(file))
        restarted.initialize()
        assertEquals(first, restarted.current)
        try {
            controller.published<String>(publish = { error("publication failed") }, describe = { recording("2") })
            fail("failure swallowed")
        } catch (_: IllegalStateException) {}
        assertEquals(first, controller.current)
        assertEquals(first, RecentRecordingStore(file).read())
    }

    @Test fun lateOlderPublicationCannotOverwriteNewerCompletion() = runBlocking {
        val controller = RecentRecordingController(RecentRecordingStore(file()))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = recording("1")
        val newer = recording("2") // Same clock tick; ordering must follow completion, not wall time.
        val delayed = launch {
            controller.published<RecentRecording>({ old }) { entered.complete(Unit); release.await(); it }
        }
        entered.await()
        controller.published<RecentRecording>({ newer }) { it }
        release.complete(Unit)
        delayed.join()
        assertEquals(newer, controller.current)
        assertEquals(newer, RecentRecordingStore(file()).read())
    }

    @Test fun clockMovingBackwardsDoesNotPreventNextSuccessfulStopFromBecomingRecent() = runBlocking {
        val store = RecentRecordingStore(file())
        store.write(recording("1"))
        val restarted = RecentRecordingController(store)
        val laterStop = recording("2").copy(completedAtMs = 123450000L)
        restarted.published<RecentRecording>({ laterStop }) { it }
        restarted.initialize()
        assertEquals(laterStop, restarted.current)
        assertEquals(laterStop, store.read())
    }

    @Test fun lazyReferenceInitializationRunsOffCallerAndCancelledReadCannotOverwriteNewStop() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val caller = Thread.currentThread()
        var reader: Thread? = null
        val target = file()
        RecentRecordingStore(target).write(recording("1"))
        val store = RecentRecordingStore {
            reader = Thread.currentThread()
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            target
        }
        assertNull(reader)
        val controller = RecentRecordingController(store)
        val initializing = launch(start = CoroutineStart.UNDISPATCHED) { controller.initialize() }
        check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val newer = recording("2")
        val remembering = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.published<RecentRecording>({ newer }) { it }
        }
        try { initializing.cancel() } finally { release.countDown() }
        initializing.join(); remembering.join()
        assertNotSame(caller, reader)
        assertEquals(newer, controller.current)
        assertEquals(newer, store.read())
    }

    @Test fun legacyPublishedFileReferenceSurvivesRestartWithoutChangingAudio() = runBlocking {
        val published = File(temporary.root, "public/recording.m4a")
        check(published.parentFile!!.mkdirs())
        published.writeBytes(byteArrayOf(1, 2, 3, 4))
        val ref = File(temporary.root, "private/recent.ref")
        val recording = recording("1").copy(uri = published.toURI().toString())
        val controller = RecentRecordingController(RecentRecordingStore(ref))
        controller.published<RecentRecording>({ recording }) { it }
        val restarted = RecentRecordingController(RecentRecordingStore(ref))
        restarted.initialize()
        assertEquals(recording, restarted.current)
        reopenRecentRecording({ null }, {})
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), published.readBytes())
        assertEquals(listOf("recording.m4a"), published.parentFile!!.list()!!.toList())
    }

    @Test fun cancellationAtPublicationDispatcherReturnAndDuringMetadataPreservesPrevious() = runBlocking {
        val store = RecentRecordingStore(file())
        val controller = RecentRecordingController(store)
        val first = recording("1")
        controller.published<RecentRecording>({ first }) { it }
        var described = false
        val atReturn = launch {
            val caller = currentCoroutineContext().job
            controller.published<RecentRecording>(publish = {
                withContext(Dispatchers.IO + NonCancellable) { caller.cancel(); recording("2") }
            }, describe = { described = true; it })
        }
        atReturn.join()
        assertFalse(described)
        val duringMetadata = launch {
            controller.published<RecentRecording>({ recording("3") }) {
                currentCoroutineContext().cancel(); it
            }
        }
        duringMetadata.join()
        assertEquals(first, controller.current)
        assertEquals(first, store.read())
    }

    @Test fun failedReferenceCommitPreservesPreviousBytesAndUnknownMetadata() = runBlocking {
        val store = RecentRecordingStore(file())
        val first = recording("1").copy(durationMs = null, channels = null, format = null)
        store.write(first)
        val before = file().readBytes()
        try { store.write(recording("2")) { throw java.io.IOException("write interrupted") }; fail() }
        catch (_: java.io.IOException) {}
        assertArrayEquals(before, file().readBytes())
        assertEquals(first, store.read())
        assertFalse(temporary.root.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun cancelledInitializationAndRepeatedInitializationCannotOverwriteRememberedRecording() = runBlocking {
        val queue = object : CoroutineDispatcher() {
            val tasks = java.util.ArrayDeque<Runnable>()
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val store = RecentRecordingStore(file())
        store.write(recording("1"))
        val controller = RecentRecordingController(store, queue)
        val initialization = launch(start = CoroutineStart.UNDISPATCHED) { controller.initialize() }
        initialization.cancel()
        queue.tasks.removeFirst().run()
        initialization.join()
        assertNull(controller.current)
        val newer = recording("2")
        val remember = launch(start = CoroutineStart.UNDISPATCHED) { controller.published<RecentRecording>({ newer }) { it } }
        queue.tasks.removeFirst().run()
        remember.join()
        controller.initialize() // already initialized by commit; must not schedule a stale read
        assertTrue(queue.tasks.isEmpty())
        assertEquals(newer, controller.current)
        assertEquals(newer, store.read())
    }

    @Test fun referenceFailureDoesNotMisreportVerifiedPublicationAndUntrackedPublicationCannotReplace() = runBlocking {
        val store = RecentRecordingStore(file())
        val first = recording("1")
        store.write(first)
        val controller = RecentRecordingController(store)
        controller.initialize()
        var referenceFailed = false
        var publishedResult: String? = null
        try {
            publishedResult = controller.published(publish = { "verified-copy" },
                onReferenceFailure = { referenceFailed = true },
                describe = { recording("2").copy(name = "") })
        } catch (_: IllegalArgumentException) { /* preimplementation failure must become an assertion RED */ }
        assertTrue("reference failure must be separate from publication failure", referenceFailed)
        assertEquals("verified-copy", publishedResult)
        var described = false
        try {
            controller.published<String>(publish = {
                throw com.typezero.resound.core.io.PublishedButUntracked("copy-without-receipt", java.io.IOException("receipt failed"))
            }, describe = { described = true; recording("3") })
            fail("untracked publication accepted")
        } catch (_: com.typezero.resound.core.io.PublishedButUntracked) {}
        assertFalse(described)
        assertEquals(first, controller.current)
        assertEquals(first, store.read())
    }

    @Test fun encodedReferenceBoundAlsoAppliesToNonAsciiFileUris() {
        val store = RecentRecordingStore(file())
        val first = recording("1")
        store.write(first)
        val invalid = first.copy(uri = File(temporary.root.parentFile, "界".repeat(3000) + ".m4a").toURI().toString())
        try { store.write(invalid); fail("oversized encoded reference accepted") }
        catch (_: IllegalArgumentException) {}
        assertEquals(first, store.read())
    }

    @Test fun recentCardOnlyAppearsWhenIdleWithoutCurrentAudio() {
        assertTrue(showRecentRecording(false, true, false, false, false))
        assertFalse(showRecentRecording(true, true, false, false, false))
        assertFalse(showRecentRecording(false, false, false, false, false))
        assertFalse(showRecentRecording(false, true, true, false, false))
        assertFalse(showRecentRecording(false, true, false, true, false))
        assertFalse(showRecentRecording(false, true, false, false, true))
    }

    @Test fun reopenRequiresActualPreviewReadinessAndAccessFailuresRemainRetryable() = runBlocking {
        val store = RecentRecordingStore(file())
        val first = recording("1")
        store.write(first)
        var clears = 0
        val wave = com.typezero.resound.core.audio.Waveform.unavailable(2500)
        val unsupported = PreviewLoad().load(2500, {}, { wave }, {}, isReady = { false })
        val failed = reopenRecentRecording({ unsupported }, { clears++ })
        assertFalse(failed.contains("Reopened"))
        assertTrue(failed.contains("unavailable", ignoreCase = true))
        assertEquals(1, clears)
        val empty = reopenRecentRecording({ null }, { clears++ })
        assertFalse(empty.contains("Reopened"))
        val denied = reopenRecentRecording({ throw SecurityException("permission denied") }, { clears++ })
        assertFalse(denied.contains("Reopened"))
        val missing = reopenRecentRecording({ throw java.io.FileNotFoundException("moved") }, { clears++ })
        assertTrue(missing.contains("Reopen"))
        assertEquals(first, store.read())
        val ready = PreviewLoad().load(2500, {}, { wave }, {}, isReady = { true })
        assertTrue(reopenRecentRecording({ ready }, { clears++ }).startsWith("Reopened"))
        assertEquals(4, clears)
        val cancelled = launch {
            reopenRecentRecording({ throw CancellationException("left Editor") }, { clears++ })
        }
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertEquals(4, clears)
        assertEquals(first, store.read())
    }

    @Test fun metadataLineNeverInventsDurationOrChannels() {
        val unknown = recording("1").copy(durationMs = null, channels = null, format = null)
        assertEquals("Metadata unknown", recentRecordingMetadata(unknown))
        assertEquals("M4A · AAC", recentRecordingMetadata(unknown.copy(format = "M4A · AAC")))
        assertEquals("0:02 · M4A · Mono", recentRecordingMetadata(recording("1")))
        assertEquals("3 channels", recentRecordingMetadata(unknown.copy(channels = 3)))
    }

    @Test fun malformedAndBoundedReferencesCannotReplacePrevious() = runBlocking {
        val file = file()
        val store = RecentRecordingStore(file)
        val first = recording("1")
        store.write(first)
        val invalid = listOf(
            first.copy(uri = "https://example.com/audio.m4a"),
            first.copy(uri = "content://other/private/1"),
            first.copy(uri = "content://media/external/audio/media/0"),
            first.copy(uri = first.uri + "?redirect=1"),
            first.copy(uri = "file:relative.m4a"),
            first.copy(uri = "x".repeat(4097)),
            first.copy(name = ""), first.copy(name = "a".repeat(257)),
            first.copy(name = "bad\nname"), first.copy(name = "../draft.m4a"),
            first.copy(completedAtMs = 0), first.copy(durationMs = -1),
            first.copy(channels = 0), first.copy(channels = 33), first.copy(format = "x".repeat(65)),
        )
        invalid.forEach { candidate ->
            try { store.write(candidate); fail("accepted invalid reference: $candidate") }
            catch (_: IllegalArgumentException) {}
            assertEquals(first, store.read())
        }
        file.appendBytes(byteArrayOf(1))
        assertNull("trailing garbage must not be accepted", store.read())
        file.writeBytes(ByteArray(8193))
        assertNull(store.read())
        file.writeBytes(byteArrayOf(0, 0, 0, 1, 127, 127))
        assertNull("truncated ref", store.read())
        store.write(first.copy(durationMs = null, channels = null, format = null))
        assertEquals(first.copy(durationMs = null, channels = null, format = null), store.read())
    }
}
