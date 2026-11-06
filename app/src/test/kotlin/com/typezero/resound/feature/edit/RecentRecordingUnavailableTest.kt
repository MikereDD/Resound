package com.typezero.resound.feature.edit

import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecentRecordingUnavailableTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun recording(id: Int = 1) = RecentRecording(
        "content://media/external/audio/media/$id", "recording_$id.m4a", 123456789L,
    )

    @Test fun clearRemovesOnlyReferenceAndStaysClearedAfterRestart() = runBlocking {
        val media = File(temporary.root, "public/recording.m4a")
        check(media.parentFile!!.mkdirs())
        media.writeBytes(byteArrayOf(1, 2, 3, 4))
        val draft = File(temporary.root, "private/drafts/retained.m4a")
        check(draft.parentFile!!.mkdirs())
        draft.writeBytes(byteArrayOf(5, 6, 7))
        val receipt = File(draft.path + ".saved").also { it.writeText("retained receipt") }
        val ref = File(temporary.root, "private/recent.ref")
        val saved = recording().copy(uri = media.toURI().toString())
        val store = RecentRecordingStore(ref)
        store.write(saved)
        val controller = RecentRecordingController(store)
        controller.initialize()
        controller.reopen(saved, { null }, {})
        assertTrue(controller.clear(saved))
        assertNull(controller.current)
        assertNull(controller.notice.value)
        assertFalse(ref.exists())
        val restarted = RecentRecordingController(store)
        restarted.initialize()
        assertNull(restarted.current)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), media.readBytes())
        assertArrayEquals(byteArrayOf(5, 6, 7), draft.readBytes())
        assertEquals("retained receipt", receipt.readText())
        assertFalse(controller.clear(saved))
    }

    @Test fun clearInvalidatesOlderMetadataButAllowsTheNextNewRecording() = runBlocking {
        val store = RecentRecordingStore(File(temporary.root, "recent.ref"))
        val saved = recording()
        store.write(saved)
        val controller = RecentRecordingController(store)
        controller.initialize()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldWork = launch {
            controller.published<RecentRecording>({ recording(2) }) {
                entered.complete(Unit)
                release.await()
                it
            }
        }
        entered.await()
        assertTrue(controller.clear(saved))
        release.complete(Unit)
        oldWork.join()
        assertNull(controller.current)
        assertNull(store.read())
        val next = recording(3)
        controller.published<RecentRecording>({ next }) { it }
        assertEquals(next, controller.current)
        assertEquals(next, store.read())
        assertNull(controller.notice.value)
    }

    @Test fun lateFailureAndStaleClearCannotChangeANewerRecording() = runBlocking {
        val store = RecentRecordingStore(File(temporary.root, "recent.ref"))
        val saved = recording()
        store.write(saved)
        val controller = RecentRecordingController(store)
        controller.initialize()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val late = launch {
            controller.reopen(saved, { entered.complete(Unit); release.await(); null }, {})
        }
        entered.await()
        val next = recording(2)
        controller.published<RecentRecording>({ next }) { it }
        release.complete(Unit)
        late.join()
        assertNull(controller.notice.value)
        assertFalse(controller.clear(saved))
        assertEquals(next, controller.current)
        assertEquals(next, store.read())
        var called = false
        controller.reopen(saved, { called = true; null }, {})
        assertFalse(called)
    }

    @Test fun clearFailureKeepsReferenceAndReadableFeedbackForRetry() = runBlocking {
        val ref = File(temporary.root, "recent.ref")
        val store = RecentRecordingStore(ref)
        val saved = recording()
        store.write(saved)
        val controller = RecentRecordingController(store)
        controller.initialize()
        controller.reopen(saved, { null }, {})
        // Simulate failed reference removal using a test-owned directory, not permissions/media.
        check(ref.delete()); check(ref.mkdir())
        val sentinel = File(ref, "sentinel").also { it.writeText("keep") }
        assertFalse(controller.clear(saved))
        assertEquals(saved, controller.current)
        assertEquals(RecentRecordingNotice(saved, clearFailed = true), controller.notice.value)
        assertEquals("keep", sentinel.readText())
        check(sentinel.delete()); check(ref.delete())
        store.write(saved)
        assertTrue(controller.clear(saved))
        assertNull(controller.current)
        assertNull(controller.notice.value)
    }

    @Test fun cancelledRetryAndCancelledClearKeepReferenceAndUnavailableNotice() = runBlocking {
        val queue = object : CoroutineDispatcher() {
            val tasks = java.util.ArrayDeque<Runnable>()
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val ref = File(temporary.root, "recent.ref")
        val store = RecentRecordingStore(ref)
        val saved = recording()
        store.write(saved)
        val controller = RecentRecordingController(store, queue)
        val initialization = launch(start = CoroutineStart.UNDISPATCHED) { controller.initialize() }
        queue.tasks.removeFirst().run(); initialization.join()
        controller.reopen(saved, { null }, {})
        val retry = launch { controller.reopen(saved, { throw CancellationException("left Editor") }, {}) }
        retry.join()
        assertTrue(retry.isCancelled)
        val clear = launch(start = CoroutineStart.UNDISPATCHED) { controller.clear(saved) }
        clear.cancel()
        queue.tasks.removeFirst().run(); clear.join()
        assertEquals(saved, controller.current)
        assertEquals(saved, store.read())
        assertEquals(RecentRecordingNotice(saved), controller.notice.value)
        assertTrue(ref.isFile)
    }

    @Test fun missingOrDeniedReopenKeepsReadableCardNoticeUntilAnExplicitSuccessfulRetry() = runBlocking {
        val file = File(temporary.root, "recent.ref")
        val store = RecentRecordingStore(file)
        val saved = recording()
        store.write(saved)
        val controller = RecentRecordingController(store)
        controller.initialize()
        val before = file.readBytes()
        var cleanup = 0
        val missing = controller.reopen(saved, { throw FileNotFoundException("deleted") }, { cleanup++ })
        assertFalse(missing.startsWith("Reopened"))
        assertEquals(RecentRecordingNotice(saved), controller.notice.value)
        controller.initialize() // A replacement Editor must not erase the sticky notice.
        assertEquals(RecentRecordingNotice(saved), controller.notice.value)
        controller.reopen(saved, { throw SecurityException("denied") }, { cleanup++ })
        assertEquals(RecentRecordingNotice(saved), controller.notice.value)
        assertEquals(saved, controller.current)
        assertArrayEquals(before, file.readBytes())
        val wave = com.typezero.resound.core.audio.Waveform.unavailable(2500)
        val ready = PreviewLoad().load(2500, {}, { wave }, {}, isReady = { true })
        assertTrue(controller.reopen(saved, { ready }, { cleanup++ }).startsWith("Reopened"))
        assertNull(controller.notice.value)
        assertEquals(2, cleanup)
    }
}
