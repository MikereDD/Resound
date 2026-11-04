package com.typezero.resound.feature.record

import com.typezero.resound.core.io.DraftStore
import com.typezero.resound.core.audio.PlayerBackend
import com.typezero.resound.core.audio.PreparedPlayer
import com.typezero.resound.core.audio.Waveform
import com.typezero.resound.feature.edit.PreviewLoad
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class RecordingRecoveryAdmissionTest {
    private fun fixture(): File = Files.createTempDirectory(
        File(System.getProperty("java.io.tmpdir")).toPath(),
        "recovery-"
    ).toFile().canonicalFile

    private class HeldRecorder : Recorder {
        val started = CountDownLatch(1)
        val startReturn = CountDownLatch(1)
        override var isRecording = false
        override fun start(outputPath: String) {
            File(outputPath).writeText("synthetic-capture")
            started.countDown()
            check(startReturn.await(10, TimeUnit.SECONDS))
            isRecording = true
        }
        override fun stop(): String? { isRecording = false; return null }
    }

    @Test fun replacementRecoveryCannotListWriterBeforeStartTokenReturns() = runBlocking {
        val dir = fixture()
        val file = File(dir, "capture.m4a")
        val native = HeldRecorder()
        val application = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = RecordingController(native, application, Dispatchers.IO)
        val start = async(Dispatchers.Default) { controller.start(file.path) }
        try {
            assertTrue(native.started.await(10, TimeUnit.SECONDS))
            assertEquals(0L, controller.active)
            // A replacement Editor creates a new store while the app-owned writer is held.
            val replacement = DraftStore(dir, controller.recoveryAdmission)
            assertFalse("start-in-flight recording must not be an actionable recovery candidate", replacement.list().contains(file))
            assertEquals("synthetic-capture", file.readText())
        } finally {
            native.startReturn.countDown()
            start.await()
            controller.stop()
            application.cancel()
        }
    }

    // Remaining cases characterize the composed controller + host DraftStore policy.
    // The counters are injected action bodies, NOT Android decoder/provider/UI integration.
    private enum class StopMode { SUCCESS, KNOWN_ERROR, RELEASE_UNKNOWN, THROWS }
    private class MatrixRecorder(
        holdStart: Boolean = false,
        holdStop: Boolean = false,
        val startFailure: Boolean = false,
        val releasedAfterStartFailure: Boolean = true,
        val mode: StopMode = StopMode.SUCCESS,
    ) : Recorder {
        val startEntered = CountDownLatch(1)
        val startReturn = CountDownLatch(if (holdStart) 1 else 0)
        val stopEntered = CountDownLatch(1)
        val stopReturn = CountDownLatch(if (holdStop) 1 else 0)
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        @Volatile override var isRecording = false
        @Volatile override var terminationAcknowledged = true
        private lateinit var path: String
        override fun start(outputPath: String) {
            path = outputPath
            starts.incrementAndGet()
            terminationAcknowledged = false
            File(path).appendText("|start-enter")
            startEntered.countDown()
            check(startReturn.await(10, TimeUnit.SECONDS)) { "finite fake start gate expired" }
            File(path).appendText("|start-return")
            if (startFailure) {
                terminationAcknowledged = releasedAfterStartFailure
                throw IllegalStateException("synthetic setup/start failure")
            }
            isRecording = true
        }
        override fun stop(): String? = stopOutcome().path
        override fun stopOutcome(): RecorderOutcome {
            stops.incrementAndGet()
            File(path).appendText("|stop-enter")
            stopEntered.countDown()
            check(stopReturn.await(10, TimeUnit.SECONDS)) { "finite fake stop gate expired" }
            File(path).appendText("|stop-return")
            isRecording = false // UI flag alone deliberately does not acknowledge release.
            terminationAcknowledged = mode == StopMode.SUCCESS || mode == StopMode.KNOWN_ERROR
            return when (mode) {
                StopMode.SUCCESS -> RecorderOutcome(path, true)
                StopMode.KNOWN_ERROR -> RecorderOutcome(null, true, IllegalStateException("synthetic stop error"))
                StopMode.RELEASE_UNKNOWN -> RecorderOutcome(path, false, IllegalStateException("synthetic release failure"))
                StopMode.THROWS -> throw IllegalStateException("synthetic throwing backend")
            }
        }
        fun releaseGates() { startReturn.countDown(); stopReturn.countDown() }
    }

    private class Dispatches {
        var decode = 0; var player = 0; var copy = 0; var publication = 0; var deletion = 0
        fun open() { decode++; player++ }
        fun save() { copy++; publication++ }
        fun discard() { deletion++ } // controlled deleter: never deletes a blocked fixture
        fun assertZero() {
            assertEquals("decode", 0, decode); assertEquals("player", 0, player)
            assertEquals("copy", 0, copy); assertEquals("publication", 0, publication)
            assertEquals("deletion", 0, deletion)
        }
    }
    private data class Leaf(val base64: String, val sha256: String, val size: Long, val mtime: Long)
    private fun inventory(dir: File): Map<String, Leaf> = dir.walkTopDown().filter { it.isFile }.associate { file ->
        val bytes = file.readBytes()
        file.relativeTo(dir).invariantSeparatorsPath to Leaf(
            Base64.getEncoder().encodeToString(bytes),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) },
            file.length(), file.lastModified(),
        )
    }.toSortedMap()
    private fun inventoryJson(value: Map<String, Leaf>): String = value.entries.joinToString(",", "{", "}") { (name, leaf) ->
        // Every leaf is a fixed synthetic test name, not an absolute/personal path.
        "\"$name\":{\"base64\":\"${leaf.base64}\",\"sha256\":\"${leaf.sha256}\",\"size\":${leaf.size},\"mtime\":${leaf.mtime}}"
    }
    private fun preserved(label: String, dir: File, before: Map<String, Leaf>) {
        val after = inventory(dir)
        println("SYNTHETIC_DRAFT_PRESERVATION={\"case\":\"$label\",\"before\":${inventoryJson(before)},\"after\":${inventoryJson(after)}}")
        assertEquals("synthetic bytes/receipts/mtime/relative leaf inventory: $label", before, after)
    }
    private class Rig(val dir: File, val native: MatrixRecorder, appDispatcher: CoroutineDispatcher = Dispatchers.Default) {
        val application = CoroutineScope(SupervisorJob() + appDispatcher)
        val controller = RecordingController(native, application, Dispatchers.IO)
        val file = File(dir, "capture.m4a").apply { writeText("synthetic-before") }
        val other = File(dir, "inactive.m4a").apply { writeText("synthetic-inactive") }
        val receipt = File(dir, "capture.m4a.saved").apply { writeText("synthetic-invalid-receipt") }
        fun store() = DraftStore(dir, controller.recoveryAdmission)
        suspend fun close() {
            native.releaseGates()
            try {
                withContext(NonCancellable) {
                    withTimeout(10_000) {
                        // Non-cancellable native work is bounded by the fake's finite gates.
                        controller.stop(controller.active)
                        application.coroutineContext[Job]!!.children.toList().forEach { it.join() }
                    }
                }
            } finally { application.cancel() }
        }
    }
    private fun awaitGate(gate: CountDownLatch) = assertTrue("finite fake gate", gate.await(5, TimeUnit.SECONDS))
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: IllegalStateException) { return failure }
          catch (failure: IllegalArgumentException) { return failure }
        throw AssertionError("expected admission rejection before action dispatch")
    }
    private suspend fun blockedActions(
        label: String, rig: Rig, candidate: RecordingRecoveryAdmission.Candidate,
        replacement: DraftStore = rig.store(),
    ) {
        val before = inventory(rig.dir)
        val effects = Dispatches()
        val open = rejected { replacement.withRecovery(candidate) { _, _ -> effects.open() } }
        effects.assertZero(); preserved("$label-open", rig.dir, before)
        rejected { replacement.withRecovery(candidate) { _, _ -> effects.save() } }
        effects.assertZero(); preserved("$label-save", rig.dir, before)
        rejected { replacement.withRecovery(candidate) { _, _ -> effects.discard() } }
        effects.assertZero(); preserved("$label-discard", rig.dir, before)
        assertFalse("unavailable reason must be observable", open.message.isNullOrBlank())
    }
    private suspend fun acceptedActions(rig: Rig, candidate: RecordingRecoveryAdmission.Candidate) {
        val effects = Dispatches()
        val before = inventory(rig.dir)
        rig.store().withRecovery(candidate) { file, _ -> assertEquals(candidate.file, file); effects.open() }
        rig.store().withRecovery(candidate) { _, _ -> effects.save() }
        rig.store().withRecovery(candidate) { _, _ -> effects.discard() }
        assertEquals(1, effects.decode); assertEquals(1, effects.player); assertEquals(1, effects.copy)
        assertEquals(1, effects.publication); assertEquals(1, effects.deletion)
        preserved("accepted-controlled-actions", rig.dir, before)
    }

    @Test fun characterizationStartingCapturingStoppingRejectReplacementAndStaleActions() = runBlocking<Unit> {
        val rig = Rig(fixture(), MatrixRecorder(holdStart = true, holdStop = true))
        val stale = rig.store().listCandidates().single { it.file == rig.file }
        val start = async(Dispatchers.Default) { rig.controller.start(rig.file.path) }
        var stop: Deferred<String?>? = null
        try {
            awaitGate(rig.native.startEntered)
            assertEquals(0L, rig.controller.active)
            assertEquals(RecordingRecoveryAdmission.State.STARTING, rig.controller.recoveryAdmission.state(rig.file))
            // Native IO is held; these bounded observations/actions do not await that native mutex.
            withTimeout(5_000) {
                withContext(Dispatchers.Default) {
                    assertFalse(rig.store().listCandidates().any { it.file == rig.file })
                    blockedActions("starting", rig, stale)
                }
            }
            rig.native.startReturn.countDown()
            val token = withTimeout(5_000) { start.await() }
            assertEquals(RecordingRecoveryAdmission.State.CAPTURING, rig.controller.recoveryAdmission.state(rig.file))
            assertEquals(token, rig.controller.active)
            assertFalse(rig.store().list().contains(rig.file))
            blockedActions("capturing", rig, stale)
            stop = async(Dispatchers.Default) { rig.controller.stop(token) }
            awaitGate(rig.native.stopEntered)
            withTimeout(5_000) {
                withContext(Dispatchers.Default) {
                    assertEquals(RecordingRecoveryAdmission.State.STOPPING, rig.controller.recoveryAdmission.state(rig.file))
                    assertFalse(rig.store().listCandidates().any { it.file == rig.file })
                    blockedActions("stopping", rig, stale)
                    // A separate inactive path remains available while A is held in native stop.
                    acceptedActions(rig, rig.store().candidate(rig.other)!!)
                }
            }
            rig.native.stopReturn.countDown()
            assertEquals(rig.file.path, withTimeout(5_000) { checkNotNull(stop).await() })
            assertEquals(RecordingRecoveryAdmission.State.FINALIZED, rig.controller.recoveryAdmission.state(rig.file))
            assertEquals("synthetic-before|start-enter|start-return|stop-enter|stop-return", rig.file.readText())
            assertEquals("synthetic-invalid-receipt", rig.receipt.readText())
            assertEquals(setOf(rig.file, rig.other), rig.store().list().toSet())
            blockedActions("version-zero-after-terminal", rig, stale)
            acceptedActions(rig, rig.store().candidate(rig.file, token)!!)
        } finally {
            rig.native.releaseGates()
            try { withContext(NonCancellable) { withTimeout(10_000) { start.join(); stop?.join() } } }
            finally { rig.close() }
        }
    }

    @Test fun characterizationCanonicalOwnerGenerationAndStaleStopIdentity() = runBlocking<Unit> {
        val rig = Rig(fixture(), MatrixRecorder())
        try {
            val alias = File(rig.dir, "unused/../capture.m4a")
            assertEquals(rig.store().candidate(rig.file), rig.store().candidate(alias))
            val stale = rig.store().candidate(alias)!!
            val foreign = DraftStore(rig.dir).candidate(rig.file)!!
            blockedActions("foreign-owner", rig, foreign)
            blockedActions("forged-generation", rig, stale.copy(generation = Long.MAX_VALUE))
            val outside = File(fixture(), "outside.m4a").apply { writeText("synthetic-outside") }
            val outsideBefore = inventory(outside.parentFile)
            rejected { rig.store().candidate(outside) }
            blockedActions("outside-root-forgery", rig, stale.copy(file = outside))
            preserved("outside-root", outside.parentFile, outsideBefore)
            rejected { rig.store().candidate(File(outside.parentFile, "missing.m4a")) }
            blockedActions("outside-missing-forgery", rig, stale.copy(file = File(outside.parentFile, "missing.m4a")))
            val emptyOutside = File(outside.parentFile, "empty.m4a").apply { writeBytes(byteArrayOf()) }
            val outsideWithEmpty = inventory(outside.parentFile)
            rejected { rig.store().candidate(emptyOutside) }
            blockedActions("outside-empty-forgery", rig, stale.copy(file = emptyOutside))
            preserved("outside-empty", outside.parentFile, outsideWithEmpty)
            val first = rig.controller.start(alias.path)
            val beforeInvalidStop = inventory(rig.dir)
            assertNull(rig.controller.stop(-1L)); assertEquals(0, rig.native.stops.get())
            assertEquals(first, rig.controller.active)
            preserved("invalid-stop-generation", rig.dir, beforeInvalidStop)
            assertEquals(rig.file.path, rig.controller.stop(first))
            assertNull(rig.store().candidate(rig.file, first + 1))
            blockedActions("old-zero-after-cycle", rig, stale)
            val finalized = rig.store().candidate(rig.file, first)!!
            val second = rig.controller.start(rig.file.path)
            assertTrue(second > first)
            val beforeStaleStop = inventory(rig.dir)
            assertNull(rig.controller.stop(first))
            assertEquals(1, rig.native.stops.get()); assertEquals(second, rig.controller.active)
            preserved("stale-stop-newer-same-path", rig.dir, beforeStaleStop)
            blockedActions("old-finalized-new-writer", rig, finalized)
            rig.controller.stop(second)
            val third = rig.controller.start(rig.other.path)
            val beforeDetached = inventory(rig.dir)
            rig.controller.stopDetached(second).let { withTimeout(5_000) { it.join() } }
            assertEquals(third, rig.controller.active); assertEquals(2, rig.native.stops.get())
            preserved("stale-detached-stop-newer-other-path", rig.dir, beforeDetached)
            // A is finalized and actionable while a newer writer owns different path B.
            acceptedActions(rig, rig.store().candidate(rig.file, second)!!)
            assertNull(rig.store().candidate(rig.other))
            rig.controller.stop(third)
        } finally { rig.close() }
    }

    @Test fun characterizationLeaseSpansSuspendedActionAndRejectsNativeWriter() = runBlocking<Unit> {
        val rig = Rig(fixture(), MatrixRecorder())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val candidate = rig.store().candidate(rig.file)!!
        val action = async(Dispatchers.Default) {
            rig.store().withRecovery(candidate) { _, _ ->
                entered.complete(Unit)
                withTimeout(10_000) { release.await() }
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            val before = inventory(rig.dir)
            rejected { rig.controller.start(rig.file.path) }
            assertEquals(0, rig.native.starts.get())
            blockedActions("action-lease-held", rig, candidate)
            assertFalse(rig.store().list().contains(rig.file))
            assertEquals(RecordingRecoveryAdmission.State.FINALIZED, rig.controller.recoveryAdmission.state(rig.file))
            preserved("writer-refused-during-action", rig.dir, before)
            release.complete(Unit)
            withTimeout(5_000) { action.await() }
            acceptedActions(rig, rig.store().candidate(rig.file)!!)
            val token = rig.controller.start(rig.file.path)
            assertEquals(1, rig.native.starts.get())
            rig.controller.stop(token)
        } finally {
            release.complete(Unit)
            try { withContext(NonCancellable) { withTimeout(10_000) { action.join() } } }
            finally { rig.close() }
        }
    }

    @Test fun characterizationLeaseSpansBlockedIoAndCancellationUntilProducerReturns() = runBlocking<Unit> {
        val rig = Rig(fixture(), MatrixRecorder())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val candidate = rig.store().candidate(rig.file)!!
        val action = launch(Dispatchers.Default) {
            rig.store().withRecovery(candidate) { _, _ ->
                withContext(Dispatchers.IO + NonCancellable) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        }
        try {
            awaitGate(entered)
            action.cancel()
            assertFalse(action.isCompleted)
            blockedActions("cancelled-producer-still-held", rig, candidate)
            rejected { rig.controller.start(rig.file.path) }
            assertEquals(0, rig.native.starts.get())
            release.countDown()
            withTimeout(5_000) { action.join() }
            acceptedActions(rig, rig.store().candidate(rig.file)!!)
        } finally {
            release.countDown()
            try { withContext(NonCancellable) { withTimeout(10_000) { action.join() } } }
            finally { rig.close() }
        }
    }

    @Test fun characterizationLeaseClosesOnThrowAndCancelButNotIndeterminateRetirement() = runBlocking<Unit> {
        val rig = Rig(fixture(), MatrixRecorder())
        val entered = CompletableDeferred<Unit>()
        var action: Job? = null
        try {
            val candidate = rig.store().candidate(rig.file)!!
            lateinit var retiredLease: RecordingRecoveryAdmission.Lease
            rig.store().withRecovery(candidate) { _, lease -> retiredLease = lease }
            rig.store().withRecovery(candidate) { _, _ ->
                retiredLease.close() // a late old close cannot clear this newer action identity
                assertNull(rig.store().candidate(rig.file))
                rejected { rig.controller.start(rig.file.path) }
                assertEquals(0, rig.native.starts.get())
            }
            val thrown = rejected { rig.store().withRecovery(candidate) { _, _ -> throw IllegalStateException("synthetic action failure") } }
            assertTrue(thrown.message!!.contains("synthetic action failure"))
            acceptedActions(rig, rig.store().candidate(rig.file)!!)
            action = launch(Dispatchers.Default) {
                rig.store().withRecovery(candidate) { _, _ ->
                    entered.complete(Unit)
                    withTimeout(10_000) { awaitCancellation() }
                }
            }
            withTimeout(5_000) { entered.await() }
            checkNotNull(action).cancel(); withTimeout(5_000) { checkNotNull(action).join() }
            acceptedActions(rig, rig.store().candidate(rig.file)!!)
            rig.store().withRecovery(candidate) { _, lease ->
                lease.retainIndeterminate("synthetic retirement unacknowledged")
                lease.close(); lease.close() // explicit close and finally must not unguard it
            }
            assertNull(rig.store().candidate(rig.file))
            assertEquals("synthetic retirement unacknowledged", rig.controller.recoveryAdmission.diagnostic(rig.file))
            blockedActions("retained-indeterminate-lease", rig, candidate)
            rejected { rig.controller.start(rig.file.path) }
            assertEquals(0, rig.native.starts.get())
        } finally {
            action?.cancel()
            try { withContext(NonCancellable) { withTimeout(10_000) { action?.join() } } }
            finally { rig.close() }
        }
    }

    @Test fun characterizationSetupStartFailureKnownReleaseVersusUnknownOwnership() = runBlocking<Unit> {
        for (released in listOf(true, false)) {
            val rig = Rig(fixture(), MatrixRecorder(startFailure = true, releasedAfterStartFailure = released))
            val stale = rig.store().candidate(rig.file)!!
            try {
                val failure = rejected { rig.controller.start(rig.file.path) }
                assertTrue(failure.message!!.contains("synthetic setup/start failure"))
                assertEquals(0L, rig.controller.active)
                assertEquals("synthetic-before|start-enter|start-return", rig.file.readText())
                assertEquals("synthetic-inactive", rig.other.readText())
                assertEquals("synthetic-invalid-receipt", rig.receipt.readText())
                assertEquals(if (released) RecordingRecoveryAdmission.State.FINALIZED else RecordingRecoveryAdmission.State.INDETERMINATE,
                    rig.controller.recoveryAdmission.state(rig.file))
                assertFalse(rig.controller.failure.isNullOrBlank())
                blockedActions("start-failed-stale-$released", rig, stale)
                if (released) {
                    acceptedActions(rig, rig.store().candidate(rig.file)!!)
                    assertTrue(rig.store().list().contains(rig.file))
                } else {
                    assertNull(rig.store().candidate(rig.file))
                    assertFalse(rig.store().list().contains(rig.file))
                    rejected { rig.controller.start(rig.other.path) }
                    assertEquals(1, rig.native.starts.get())
                }
            } finally { rig.close() }
        }
    }

    @Test fun characterizationStopOutcomeRequiresAcknowledgementNotStoppedPathOrUiFlag() = runBlocking<Unit> {
        for (mode in listOf(StopMode.KNOWN_ERROR, StopMode.RELEASE_UNKNOWN, StopMode.THROWS)) {
            val rig = Rig(fixture(), MatrixRecorder(mode = mode))
            val stale = rig.store().candidate(rig.file)!!
            try {
                val token = rig.controller.start(rig.file.path)
                if (mode == StopMode.THROWS) {
                    val failure = rejected { rig.controller.stop(token) }
                    assertTrue(failure.message!!.contains("synthetic throwing backend"))
                } else assertNull("no publication path from failed or unacknowledged stop", rig.controller.stop(token))
                assertEquals(0L, rig.controller.active)
                assertFalse(rig.native.isRecording)
                assertFalse(rig.controller.failure.isNullOrBlank())
                assertEquals("synthetic-before|start-enter|start-return|stop-enter|stop-return", rig.file.readText())
                assertEquals("synthetic-invalid-receipt", rig.receipt.readText())
                blockedActions("stop-outcome-$mode", rig, stale)
                if (mode == StopMode.KNOWN_ERROR) {
                    // FINALIZED proves writer quiescence, NOT that partial m4a decodes.
                    assertEquals(RecordingRecoveryAdmission.State.FINALIZED, rig.controller.recoveryAdmission.state(rig.file))
                    acceptedActions(rig, rig.store().candidate(rig.file, token)!!)
                } else {
                    assertEquals(RecordingRecoveryAdmission.State.INDETERMINATE, rig.controller.recoveryAdmission.state(rig.file))
                    assertFalse(rig.store().list().contains(rig.file))
                    assertNull(rig.store().candidate(rig.file, token))
                    rejected { rig.controller.start(rig.other.path) }
                    assertEquals(1, rig.native.starts.get())
                    assertNull(rig.controller.stop(token)); assertEquals(1, rig.native.stops.get())
                }
            } finally { rig.close() }
        }
    }

    @Test fun characterizationLegacyBackendWithoutReleaseAcknowledgementFailsClosed() = runBlocking<Unit> {
        val dir = fixture()
        val file = File(dir, "capture.m4a").apply { writeText("synthetic-legacy") }
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val native = object : Recorder {
            override var isRecording = false
            override fun start(outputPath: String) { isRecording = true }
            override fun stop(): String { isRecording = false; return file.path }
        }
        val controller = RecordingController(native, app, Dispatchers.IO)
        val store = DraftStore(dir, controller.recoveryAdmission)
        try {
            val before = inventory(dir)
            val stale = store.candidate(file)!!
            val token = controller.start(file.path)
            assertNull(controller.stop(token))
            assertFalse(native.isRecording); assertFalse(native.terminationAcknowledged)
            assertEquals(RecordingRecoveryAdmission.State.INDETERMINATE, controller.recoveryAdmission.state(file))
            assertTrue(store.listCandidates().isEmpty())
            val effects = Dispatches()
            rejected { store.withRecovery(stale) { _, _ -> effects.open() } }
            effects.assertZero(); preserved("legacy-unacknowledged-open", dir, before)
            rejected { store.withRecovery(stale) { _, _ -> effects.save() } }
            effects.assertZero(); preserved("legacy-unacknowledged-save", dir, before)
            rejected { store.withRecovery(stale) { _, _ -> effects.discard() } }
            effects.assertZero(); preserved("legacy-unacknowledged-discard", dir, before)
            rejected { controller.start(File(dir, "inactive.m4a").path) }
        } finally { app.cancel() }
    }

    // Explicit queue lets cancellation happen AFTER IO finished but BEFORE caller delivery.
    // No sleeps, test scheduler dependency, or timing assumption about thread scheduling.
    private class ReturnQueue : CoroutineDispatcher() {
        private class Once(private val delegate: Runnable) : Runnable {
            private val ran = java.util.concurrent.atomic.AtomicBoolean()
            override fun run() { if (ran.compareAndSet(false, true)) delegate.run() }
        }
        private val queue = LinkedBlockingQueue<Runnable>()
        private val taken = mutableListOf<Runnable>() // controlled only by the test thread
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(Once(block)) }
        fun take(): Runnable = checkNotNull(queue.poll(5, TimeUnit.SECONDS)) {
            "finite dispatcher queue wait expired"
        }.also { taken += it }
        fun runNext() { take().run() }
        fun drain() {
            // A failed assertion after take() must not strand a removed return continuation.
            val claimed = taken.toList(); taken.clear(); claimed.forEach { it.run() }
            repeat(100) { val next = queue.poll() ?: return; next.run() }
            check(queue.isEmpty())
        }
        fun finish(job: Job) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            drain()
            while (!job.isCompleted) {
                queue.poll(100, TimeUnit.MILLISECONDS)?.run()
                check(System.nanoTime() < deadline) { "bounded dispatcher drain expired" }
            }
            drain()
        }
    }

    @Test fun characterizationCancellationDuringHeldStartKeepsOwnershipUntilOwnStop() = runBlocking<Unit> {
        val rig = Rig(fixture(), MatrixRecorder(holdStart = true, holdStop = true))
        val stale = rig.store().candidate(rig.file)!!
        val screen = launch(Dispatchers.Default) { rig.controller.start(rig.file.path) }
        try {
            awaitGate(rig.native.startEntered)
            screen.cancel()
            assertFalse(screen.isCompleted)
            blockedActions("cancelled-start-held", rig, stale)
            val competingStart = launch(start = CoroutineStart.UNDISPATCHED) { rig.controller.start(rig.other.path) }
            try {
                assertFalse(competingStart.isCompleted)
                assertEquals(1, rig.native.starts.get())
            } finally {
                competingStart.cancel()
                withContext(NonCancellable) { withTimeout(5_000) { competingStart.join() } }
            }
            rig.native.startReturn.countDown()
            awaitGate(rig.native.stopEntered) // cancellation cleanup owns precisely the first ticket
            assertEquals(1, rig.native.starts.get())
            assertEquals(RecordingRecoveryAdmission.State.STOPPING, rig.controller.recoveryAdmission.state(rig.file))
            blockedActions("cancelled-start-cleanup-stop-held", rig, stale)
            rig.native.stopReturn.countDown()
            withTimeout(5_000) { screen.join(); rig.application.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            assertEquals(1, rig.native.stops.get())
            assertEquals(RecordingRecoveryAdmission.State.FINALIZED, rig.controller.recoveryAdmission.state(rig.file))
            assertEquals("synthetic-before|start-enter|start-return|stop-enter|stop-return", rig.file.readText())
            assertEquals("synthetic-invalid-receipt", rig.receipt.readText())
            acceptedActions(rig, rig.store().candidate(rig.file)!!)
        } finally {
            rig.native.releaseGates()
            try { withContext(NonCancellable) { withTimeout(10_000) { screen.join() } } }
            finally { rig.close() }
        }
    }

    @Test fun characterizationCancelledStartReturnCleanupCannotStopNewerWriter() = runBlocking<Unit> {
        val appQueue = ReturnQueue()
        val callerQueue = ReturnQueue()
        val rig = Rig(fixture(), MatrixRecorder(holdStart = true), appQueue)
        val screen = launch(callerQueue) { rig.controller.start(rig.file.path) }
        try {
            callerQueue.runNext()
            awaitGate(rig.native.startEntered)
            rig.native.startReturn.countDown()
            val returnDelivery = callerQueue.take() // IO has returned, active ticket now published
            val old = rig.controller.active
            assertTrue(old > 0)
            screen.cancel()
            returnDelivery.run()
            assertTrue(screen.isCompleted); assertTrue(screen.isCancelled)
            // Deliberately defer cleanup, retire old normally, then establish newer B first.
            rig.controller.stop(old)
            val newer = rig.controller.start(rig.other.path)
            val beforeCleanup = inventory(rig.dir)
            appQueue.runNext()
            assertEquals(newer, rig.controller.active)
            assertEquals(1, rig.native.stops.get()); assertEquals(2, rig.native.starts.get())
            preserved("cancelled-start-ticket-cleanup-newer-writer", rig.dir, beforeCleanup)
            assertNull(rig.store().candidate(rig.other))
            rig.controller.stop(newer)
        } finally {
            rig.native.releaseGates(); screen.cancel()
            try {
                callerQueue.finish(screen)
                rig.application.coroutineContext[Job]!!.children.toList().forEach { appQueue.finish(it) }
            } finally { rig.close() }
        }
    }

    @Test fun characterizationCancelledHeldStopFinishesOrRetainsOwnedUncertainty() = runBlocking<Unit> {
        for (mode in listOf(StopMode.SUCCESS, StopMode.RELEASE_UNKNOWN)) {
            val rig = Rig(fixture(), MatrixRecorder(holdStop = true, mode = mode))
            val stale = rig.store().candidate(rig.file)!!
            var screen: Job? = null
            try {
                val token = rig.controller.start(rig.file.path)
                screen = launch(Dispatchers.Default) { rig.controller.stop(token) }
                awaitGate(rig.native.stopEntered)
                checkNotNull(screen).cancel()
                assertFalse(checkNotNull(screen).isCompleted)
                blockedActions("cancelled-stop-held-$mode", rig, stale)
                rig.native.stopReturn.countDown()
                withTimeout(5_000) { checkNotNull(screen).join() }
                assertEquals(1, rig.native.stops.get()); assertEquals(0L, rig.controller.active)
                assertEquals("synthetic-before|start-enter|start-return|stop-enter|stop-return", rig.file.readText())
                assertEquals("synthetic-invalid-receipt", rig.receipt.readText())
                if (mode == StopMode.SUCCESS) acceptedActions(rig, rig.store().candidate(rig.file, token)!!)
                else {
                    assertEquals(RecordingRecoveryAdmission.State.INDETERMINATE, rig.controller.recoveryAdmission.state(rig.file))
                    blockedActions("cancelled-stop-unknown", rig, stale)
                    rejected { rig.controller.start(rig.other.path) }
                    assertEquals(1, rig.native.starts.get())
                }
            } finally {
                rig.native.releaseGates()
                try { withContext(NonCancellable) { withTimeout(10_000) { screen?.join() } } }
                finally { rig.close() }
            }
        }
    }

    @Test fun characterizationCancelledStopReturnDoesNotTouchNewerSession() = runBlocking<Unit> {
        val rig = Rig(fixture(), MatrixRecorder(holdStop = true))
        val callerQueue = ReturnQueue()
        var screen: Job? = null
        try {
            val token = rig.controller.start(rig.file.path)
            screen = launch(callerQueue) { rig.controller.stop(token) }
            callerQueue.runNext()
            awaitGate(rig.native.stopEntered)
            rig.native.stopReturn.countDown()
            val delivery = callerQueue.take()
            assertEquals(RecordingRecoveryAdmission.State.FINALIZED, rig.controller.recoveryAdmission.state(rig.file))
            checkNotNull(screen).cancel(); delivery.run()
            assertTrue(checkNotNull(screen).isCompleted); assertTrue(checkNotNull(screen).isCancelled)
            acceptedActions(rig, rig.store().candidate(rig.file, token)!!)
            val newer = rig.controller.start(rig.other.path)
            assertNull(rig.controller.stop(token))
            assertEquals(newer, rig.controller.active); assertEquals(1, rig.native.stops.get())
            rig.controller.stop(newer)
        } finally {
            rig.native.releaseGates(); screen?.cancel()
            try { screen?.let { callerQueue.finish(it) } } finally { rig.close() }
        }
    }

    @Test fun characterizationCancelledLeaseAcquisitionReturnClosesBeforeActionDispatch() = runBlocking<Unit> {
        val rig = Rig(fixture(), MatrixRecorder())
        val callerQueue = ReturnQueue()
        val candidate = rig.store().candidate(rig.file)!!
        val effects = Dispatches()
        val before = inventory(rig.dir)
        val screen = launch(callerQueue) {
            rig.store().withRecovery(candidate) { _, _ -> effects.open(); effects.save(); effects.discard() }
        }
        try {
            callerQueue.runNext()
            val delivery = callerQueue.take() // acquisition completed on IO; actual action has not run
            assertNull(rig.store().candidate(rig.file))
            rejected { rig.controller.start(rig.file.path) }
            assertEquals(0, rig.native.starts.get())
            screen.cancel(); delivery.run()
            assertTrue(screen.isCompleted); assertTrue(screen.isCancelled)
            effects.assertZero(); preserved("cancelled-lease-acquisition-return", rig.dir, before)
            acceptedActions(rig, rig.store().candidate(rig.file)!!)
        } finally {
            screen.cancel()
            try { callerQueue.finish(screen) } finally { rig.close() }
        }
    }

    @Test fun interim01FailedNativeCandidateReleaseMustKeepRecoveryExcluded() = runBlocking<Unit> {
        val rig = Rig(fixture(), MatrixRecorder())
        val releaseEntered = CountDownLatch(1)
        val releaseReturn = CountDownLatch(1)
        val releases = AtomicInteger()
        val player = PreparedPlayer(retirementBudgetMs = 15_000)
        val candidate = rig.store().candidate(rig.file)!!
        val before = inventory(rig.dir)
        var acquiredSource: java.io.InputStream? = null
        val backend = object : PlayerBackend {
            override fun prepare() {
                acquiredSource = candidate.file.inputStream()
                throw IllegalStateException("synthetic native prepare failed after source acquisition")
            }
            override fun release() {
                releases.incrementAndGet()
                releaseEntered.countDown()
                check(releaseReturn.await(10, TimeUnit.SECONDS)) { "finite candidate release gate expired" }
                throw IllegalStateException("synthetic candidate native release failed")
            }
            override fun start() = Unit
            override fun pause() = Unit
            override fun seekTo(ms: Long) = Unit
            override val isPlaying = false
            override val currentMs = 0L
        }
        val action = async(Dispatchers.Default) {
            rig.store().withRecovery(candidate) { _, lease ->
                lease.withPreviewPreparation(awaitRetirement = { player.awaitRetirement() }) { nativeAttempt ->
                    var preparationSucceeded = false
                    PreviewLoad().load(
                        durationMs = 1_000L,
                        accept = { player.invalidate(); player.awaitRetirement() },
                        decode = { Waveform.unavailable(1_000L) },
                        prepare = {
                            // Same callback placement as Editor, before native allocation.
                            nativeAttempt()
                            player.prepare(candidate.file.path) { backend }
                            preparationSucceeded = true
                        },
                        isReady = { player.readyFor(candidate.file.path) },
                    )
                    preparationSucceeded
                }
            }
        }
        try {
            awaitGate(releaseEntered)
            assertNotNull("failed candidate acquired the synthetic source before retirement", acquiredSource)
            blockedActions("interim01-candidate-release-held", rig, candidate)
            rejected { rig.controller.start(rig.file.path) }
            assertEquals(0, rig.native.starts.get())
            assertFalse(action.isCompleted)
            releaseReturn.countDown()
            val outcome = withTimeout(5_000) { action.await() }
            assertTrue(outcome.nativePreparationAttempted)
            assertFalse(outcome.preparationSucceeded)
            assertEquals(1, releases.get())
            assertFalse(player.readyFor(candidate.file.path)) // not evidence of native termination
            preserved("interim01-failed-candidate-release", rig.dir, before)
            assertNull("RP01-INTERIM-01: failed native candidate release must keep recovery excluded after awaitRetirement returns", rig.store().candidate(rig.file))
            assertFalse("RP01-INTERIM-01: protected draft needs a diagnostic", rig.controller.recoveryAdmission.diagnostic(rig.file).isNullOrBlank())
            blockedActions("interim01-candidate-release-failed", rig, candidate)
            rejected { rig.controller.start(rig.file.path) }
            assertEquals(0, rig.native.starts.get())
        } finally {
            releaseReturn.countDown()
            try {
                withContext(NonCancellable) {
                    withTimeout(10_000) { action.join(); player.release(); player.awaitClosed() }
                }
            } finally {
                // Test-only host handle drain, AFTER the policy assertions and worker completion.
                try { acquiredSource?.close() } finally { rig.close() }
            }
        }
    }

    @Test fun interim02SameReplacementObserverMustDiscoverDetachedStopFinalization() = runBlocking<Unit> {
        val rig = Rig(fixture(), MatrixRecorder(holdStop = true))
        val updates = Channel<DraftStore.RecoveryListing>(Channel.UNLIMITED)
        var observer: Job? = null
        var stop: Job? = null
        try {
            val token = rig.controller.start(rig.file.path)
            stop = rig.controller.stopDetached(token)
            awaitGate(rig.native.stopEntered)
            val replacement = rig.store()
            observer = launch(Dispatchers.Default) {
                // Actual flow collected by Editor; no manually injected refresh or terminal callback.
                replacement.recoveryUpdates().collect { updates.send(it) }
            }
            val initial = withTimeout(5_000) { updates.receive() }
            assertEquals(RecordingRecoveryAdmission.State.STOPPING, rig.controller.recoveryAdmission.state(rig.file))
            assertFalse(initial.candidates.any { it.file == rig.file })
            rig.native.stopReturn.countDown()
            withTimeout(5_000) { checkNotNull(stop).join() }
            assertEquals(RecordingRecoveryAdmission.State.FINALIZED, rig.controller.recoveryAdmission.state(rig.file))
            val beforeObservation = inventory(rig.dir)
            val finalized = withTimeoutOrNull(2_000) {
                var available: DraftStore.RecoveryListing
                do { available = updates.receive() }
                while (available.candidates.none { it.file == rig.file && it.generation == token })
                available
            }
            preserved("interim02-terminal-observation", rig.dir, beforeObservation)
            assertNotNull("RP01-INTERIM-02: same replacement recovery observer must receive finalized availability without unrelated refresh", finalized)
        } finally {
            rig.native.releaseGates()
            observer?.cancel()
            try {
                withContext(NonCancellable) { withTimeout(10_000) { observer?.join(); stop?.join() } }
            } finally { updates.close(); rig.close() }
        }
    }

    @Test fun previewPolicyKeepsSuccessAndPreNativeRejectionUsable() = runBlocking<Unit> {
        for (attemptNative in listOf(false, true)) {
            val rig = Rig(fixture(), MatrixRecorder())
            try {
                val before = inventory(rig.dir)
                var uncertain = 0
                var retired = 0
                val outcome = rig.store().withRecovery(rig.store().candidate(rig.file)!!) { _, lease ->
                    lease.withPreviewPreparation(
                        awaitRetirement = { retired++ },
                        onUncertain = { uncertain++ },
                    ) { markNative ->
                        if (attemptNative) markNative()
                        attemptNative
                    }
                }
                assertEquals(attemptNative, outcome.nativePreparationAttempted)
                assertEquals(attemptNative, outcome.preparationSucceeded)
                assertEquals(1, retired)
                assertEquals(0, uncertain)
                acceptedActions(rig, rig.store().candidate(rig.file)!!)
                preserved("preview-compatible-$attemptNative", rig.dir, before)
            } finally { rig.close() }
        }
    }

    @Test fun previewPolicyProtectsFailedAbortedAndUnattestedNativeWork() = runBlocking<Unit> {
        for (mode in listOf("failed-native", "aborted-native", "failed-retirement")) {
            val rig = Rig(fixture(), MatrixRecorder())
            try {
                val candidate = rig.store().candidate(rig.file)!!
                val before = inventory(rig.dir)
                var uncertain = 0
                var retired = 0
                var cancelled = false
                try {
                    rig.store().withRecovery(candidate) { _, lease ->
                        lease.withPreviewPreparation(
                            awaitRetirement = {
                                retired++
                                if (mode == "failed-retirement") error("synthetic retirement uncertainty")
                            },
                            onUncertain = { uncertain++ },
                        ) { markNative ->
                            markNative()
                            if (mode == "aborted-native") throw CancellationException("synthetic prepare abort")
                            mode == "failed-retirement"
                        }
                    }
                } catch (_: CancellationException) { cancelled = true }
                assertEquals(mode == "aborted-native", cancelled)
                assertEquals(1, retired)
                assertEquals(1, uncertain)
                assertNull(rig.store().candidate(rig.file))
                assertFalse(rig.controller.recoveryAdmission.diagnostic(rig.file).isNullOrBlank())
                blockedActions("preview-uncertain-$mode", rig, candidate)
                rejected { rig.controller.start(rig.file.path) }
                assertEquals(0, rig.native.starts.get())
                preserved("preview-uncertain-$mode-final", rig.dir, before)
            } finally { rig.close() }
        }
    }

    @Test fun inspectionContentionWaitsWithoutFeedbackAndRechecksCancellationAndGeneration() = runBlocking<Unit> {
        for (mode in listOf("return", "cancel", "stale")) {
            val rig = Rig(fixture(), MatrixRecorder())
            val candidate = rig.store().candidate(rig.file)!!
            val owner = candidate.owner
            val inspection = owner.acquire(candidate, inspection = true)
            val initialRevision = owner.lifecycleChanges.value
            var acquired: RecordingRecoveryAdmission.Lease? = null
            var failure: Throwable? = null
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                try { acquired = owner.acquire(candidate) }
                catch (c: CancellationException) { throw c }
                catch (t: Throwable) { failure = t }
            }
            try {
                assertTrue("RP01-FINAL-02: an inspection must suspend admission rather than reject real work", waiter.isActive)
                assertNull(acquired)
                assertNull(failure)
                assertNotNull("Inspection alone must not invalidate a versioned candidate", rig.store().candidate(rig.file))
                assertEquals(initialRevision, owner.lifecycleChanges.value)
                if (mode == "cancel") waiter.cancelAndJoin()
                inspection.close()
                // Waiting admission resumes on this runBlocking dispatcher after the new writer wins.
                val writer = if (mode == "stale") owner.beginWriter(rig.file) else null
                withTimeout(5_000) { waiter.join() }
                when (mode) {
                    "return" -> { assertNotNull(acquired); assertNull(failure) }
                    "cancel" -> { assertNull(acquired); assertNull(failure); assertTrue(waiter.isCancelled) }
                    "stale" -> { assertNull(acquired); assertTrue(failure is IllegalStateException) }
                }
                acquired?.close()
                if (writer != null) owner.transition(writer, RecordingRecoveryAdmission.State.FINALIZED)
                val revisionAfterWork = owner.lifecycleChanges.value
                inspection.close(); acquired?.close()
                rig.store().listCandidates()
                assertEquals("Inspection and obsolete closure must not feed back into UI observation", revisionAfterWork, owner.lifecycleChanges.value)
                acceptedActions(rig, rig.store().candidate(rig.file)!!)
            } finally {
                inspection.close(); acquired?.close(); waiter.cancel()
                try { withContext(NonCancellable) { withTimeout(10_000) { waiter.join() } } }
                finally { rig.close() }
            }
        }
    }

    @Test fun replacementObserverRediscoversActionAfterReturnCancelOrFailure() = runBlocking<Unit> {
        for (mode in listOf("return", "cancel", "failure")) {
            val rig = Rig(fixture(), MatrixRecorder())
            val updates = Channel<DraftStore.RecoveryListing>(Channel.UNLIMITED)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var action: Job? = null
            var observer: Job? = null
            try {
                val candidate = rig.store().candidate(rig.file)!!
                val before = inventory(rig.dir)
                action = launch(Dispatchers.Default) {
                    try {
                        rig.store().withRecovery(candidate) { _, _ ->
                            entered.complete(Unit)
                            withTimeout(10_000) { release.await() }
                            if (mode == "failure") error("synthetic action failure")
                        }
                    } catch (c: CancellationException) { throw c }
                    catch (failure: IllegalStateException) {
                        check(mode == "failure" && failure.message == "synthetic action failure")
                    }
                }
                withTimeout(5_000) { entered.await() }
                observer = launch(Dispatchers.Default) { rig.store().recoveryUpdates().collect { updates.send(it) } }
                val initial = withTimeout(5_000) { updates.receive() }
                assertTrue(initial.candidates.none { it.file == rig.file })
                assertTrue(initial.protections.any { it.file == rig.file })
                blockedActions("replacement-held-action-$mode", rig, candidate)
                if (mode == "cancel") action.cancel() else release.complete(Unit)
                withTimeout(5_000) { action.join() }
                val available = withTimeoutOrNull(2_000) {
                    var next: DraftStore.RecoveryListing
                    do { next = updates.receive() }
                    while (next.candidates.none { it.file == rig.file && it.generation == candidate.generation })
                    next
                }
                assertNotNull("RP01-FINAL-01: same replacement observer must discover real action completion without refresh ($mode)", available)
                acceptedActions(rig, rig.store().candidate(rig.file)!!)
                preserved("replacement-action-completed-$mode", rig.dir, before)
            } finally {
                release.complete(Unit)
                action?.cancel(); observer?.cancel()
                try { withContext(NonCancellable) { withTimeout(10_000) { action?.join(); observer?.join() } } }
                finally { updates.close(); rig.close() }
            }
        }
    }

    @Test fun replacementObserverReportsUncertaintyWithoutListingFeedback() = runBlocking<Unit> {
        val rig = Rig(fixture(), MatrixRecorder(holdStop = true, mode = StopMode.RELEASE_UNKNOWN))
        val updates = Channel<DraftStore.RecoveryListing>(Channel.UNLIMITED)
        var observer: Job? = null
        var stop: Job? = null
        try {
            val token = rig.controller.start(rig.file.path)
            stop = rig.controller.stopDetached(token)
            awaitGate(rig.native.stopEntered)
            observer = launch(Dispatchers.Default) { rig.store().recoveryUpdates().collect { updates.send(it) } }
            val initial = withTimeout(5_000) { updates.receive() }
            assertTrue(initial.protections.any { it.file == rig.file && it.state == RecordingRecoveryAdmission.State.STOPPING })
            assertTrue(initial.candidates.none { it.file == rig.file })
            rig.native.stopReturn.countDown()
            withTimeout(5_000) { stop.join() }
            val terminal = withTimeout(5_000) {
                var next: DraftStore.RecoveryListing
                do { next = updates.receive() }
                while (next.protections.none { it.file == rig.file && it.state == RecordingRecoveryAdmission.State.INDETERMINATE })
                next
            }
            assertTrue(terminal.candidates.none { it.file == rig.file })
            assertFalse(terminal.protections.single { it.file == rig.file }.diagnostic.isNullOrBlank())
            val revision = rig.controller.recoveryAdmission.lifecycleChanges.value
            repeat(3) { rig.store().listCandidates() }
            assertEquals("listing's own temporary leases must not publish refresh events", revision,
                rig.controller.recoveryAdmission.lifecycleChanges.value)
            val before = inventory(rig.dir)
            rig.store().requestRecoveryRefresh()
            val refreshed = withTimeout(5_000) { updates.receive() }
            assertTrue(refreshed.protections.any { it.file == rig.file })
            preserved("observer-uncertain-explicit-refresh", rig.dir, before)
        } finally {
            rig.native.releaseGates()
            observer?.cancel()
            try { withContext(NonCancellable) { withTimeout(10_000) { observer?.join(); stop?.join() } } }
            finally { updates.close(); rig.close() }
        }
    }

    @Test fun characterizationInactiveHostStoreReceiptAndNoOpPurgeCompatibility() = runBlocking<Unit> {
        val rig = Rig(fixture(), MatrixRecorder())
        val publishedCopy = File(fixture(), "published.m4a").apply { writeBytes(rig.other.readBytes()) }
        try {
            val standalone = DraftStore(rig.dir)
            assertEquals(setOf(rig.file, rig.other), standalone.list().toSet())
            // Real host adapter checks separate-copy bytes, not just a receipt-shaped token.
            standalone.markPublished(rig.other, publishedCopy.toURI().toString())
            assertTrue(standalone.isPublished(rig.other))
            assertFalse(standalone.list().contains(rig.other))
            assertFalse(standalone.isPublished(rig.file)) // malformed existing receipt remains recoverable
            val before = inventory(rig.dir)
            assertEquals(0, standalone.purgePublished())
            assertEquals(0, rig.store().purgePublished(setOf(rig.other)))
            preserved("no-op-purge-published-and-malformed", rig.dir, before)
            publishedCopy.writeText("synthetic-mismatched-copy")
            assertFalse(standalone.isPublished(rig.other))
            assertTrue(standalone.list().contains(rig.other))
            acceptedActions(rig, rig.store().candidate(rig.other)!!)
            assertEquals("synthetic-inactive", rig.other.readText())
            assertEquals("synthetic-invalid-receipt", rig.receipt.readText())
        } finally { rig.close() }
    }
}
