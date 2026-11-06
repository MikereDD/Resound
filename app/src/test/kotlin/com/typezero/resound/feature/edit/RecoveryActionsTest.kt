package com.typezero.resound.feature.edit

import com.typezero.resound.core.io.DraftStore
import com.typezero.resound.core.io.publishAndTrack
import com.typezero.resound.core.io.publishUnlessAlreadyPublished
import com.typezero.resound.feature.record.RecordingRecoveryAdmission
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Host policy/store integration; injected readers are not Android parser/codec validation. */
class RecoveryActionsTest {
    private fun fixture(): File = Files.createTempDirectory(
        File(checkNotNull(System.getProperty("java.io.tmpdir"))).toPath(), "health-"
    ).toFile().canonicalFile

    @Test fun readyPreviewWithoutRealNonemptyDecodeRemainsUnverified() = runBlocking {
        val dir = fixture()
        try {
            val store = DraftStore(dir)
            val file = store.newFile("recording", "m4a").apply { writeText("original") }
            val waves = listOf(null,
                com.typezero.resound.core.audio.Waveform.unavailable(100),
                com.typezero.resound.core.audio.Waveform(floatArrayOf(), floatArrayOf(), 100),
                com.typezero.resound.core.audio.Waveform(floatArrayOf(-.2f), floatArrayOf(.5f), 100,
                    com.typezero.resound.core.audio.WaveformProvenance.SYNTHETIC))
            for (wave in waves) {
                var health = RecoveryHealth.PLAYABLE
                val actions = RecoveryActions({ _, _ -> RecoveryReadOutcome.UNVERIFIED }) { health = it }
                store.withRecovery(store.candidate(file)!!) { admitted, lease ->
                    actions.open(admitted, lease) { PreviewLoad.Result(wave, true, null) }
                }
                assertEquals(RecoveryHealth.UNVERIFIED, health)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun laterPlaybackFailureRevokesPlayableLastCheckEvidence() = runBlocking {
        val dir = fixture()
        try {
            val store = DraftStore(dir)
            val file = store.newFile("recording", "m4a").apply { writeText("original") }
            var health = RecoveryHealth.UNVERIFIED
            val actions = RecoveryActions({ _, _ -> RecoveryReadOutcome.UNVERIFIED }) { health = it }
            store.withRecovery(store.candidate(file)!!) { admitted, lease ->
                actions.open(admitted, lease) {
                    PreviewLoad().load(100, {}, {
                        com.typezero.resound.core.audio.Waveform(floatArrayOf(-.2f), floatArrayOf(.5f), 100)
                    }, {})
                }
            }
            assertEquals(RecoveryHealth.PLAYABLE, health)
            actions.playbackUnavailable()
            assertEquals(RecoveryHealth.UNVERIFIED, health)
            assertEquals("original", file.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun backgroundPauseFailureRevokesPlayableRecoveryEvidence() = runBlocking {
        val dir = fixture()
        try {
            val store = DraftStore(dir)
            val file = store.newFile("recording", "m4a").apply { writeText("original") }
            var health = RecoveryHealth.UNVERIFIED
            val actions = RecoveryActions({ _, _ -> RecoveryReadOutcome.UNVERIFIED }) { health = it }
            store.withRecovery(store.candidate(file)!!) { admitted, lease ->
                actions.open(admitted, lease) {
                    PreviewLoad().load(100, {}, {
                        com.typezero.resound.core.audio.Waveform(floatArrayOf(-.2f), floatArrayOf(.5f), 100)
                    }, {})
                }
            }
            assertEquals(RecoveryHealth.PLAYABLE, health)
            var playbackReady = true
            pausePlaybackForBackground(
                pause = { throw IllegalStateException("native pause failed") },
                onPlaybackUnavailable = { playbackReady = false },
                recoveryActions = actions,
            )
            assertFalse(playbackReady)
            assertEquals(RecoveryHealth.UNVERIFIED, health)
            assertEquals("original", file.readText())
            assertNotNull(store.candidate(file))
        } finally { dir.deleteRecursively() }
    }

    @Test fun successfulBackgroundPauseRetainsPlayableRecoveryEvidence() = runBlocking {
        val dir = fixture()
        try {
            val store = DraftStore(dir)
            val file = store.newFile("recording", "m4a").apply { writeText("original") }
            var health = RecoveryHealth.UNVERIFIED
            val actions = RecoveryActions({ _, _ -> RecoveryReadOutcome.UNVERIFIED }) { health = it }
            store.withRecovery(store.candidate(file)!!) { admitted, lease ->
                actions.open(admitted, lease) {
                    PreviewLoad().load(100, {}, {
                        com.typezero.resound.core.audio.Waveform(floatArrayOf(-.2f), floatArrayOf(.5f), 100)
                    }, {})
                }
            }
            assertEquals(RecoveryHealth.PLAYABLE, health)
            var pauses = 0
            var playbackReady = true
            pausePlaybackForBackground(
                pause = { pauses++ },
                onPlaybackUnavailable = { playbackReady = false },
                recoveryActions = actions,
            )
            assertEquals(1, pauses)
            assertTrue(playbackReady)
            assertEquals(RecoveryHealth.PLAYABLE, health)
            assertEquals("original", file.readText())
            assertNotNull(store.candidate(file))
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancelledBackgroundPausePropagatesWithoutFailureSideEffects() {
        val cancellation = kotlinx.coroutines.CancellationException("pause cancelled")
        var health = RecoveryHealth.PLAYABLE
        val actions = RecoveryActions({ _, _ -> RecoveryReadOutcome.UNVERIFIED }) { health = it }
        var playbackReady = true
        try {
            pausePlaybackForBackground(
                pause = { throw cancellation },
                onPlaybackUnavailable = { playbackReady = false },
                recoveryActions = actions,
            )
            fail("Cancellation must escape the lifecycle pause boundary")
        } catch (caught: kotlinx.coroutines.CancellationException) {
            assertSame(cancellation, caught)
        }
        assertTrue(playbackReady)
        assertEquals(RecoveryHealth.PLAYABLE, health)
    }

    @Test fun backgroundPauseFailureWithoutRecoverySourceStillClearsReadiness() {
        var failures = 0
        var playbackReady = true
        pausePlaybackForBackground(
            pause = { throw IllegalStateException("native pause failed") },
            onPlaybackUnavailable = { failures++; playbackReady = false },
            recoveryActions = null,
        )
        assertEquals(1, failures)
        assertFalse(playbackReady)
    }

    @Test fun saveDoesNotPublishWhenStaleRowHasNoOriginalBytes() = runBlocking {
        val dir = fixture()
        try {
            val store = DraftStore(dir)
            val file = store.newFile("recording", "m4a").apply { writeText("original") }
            val candidate = store.candidate(file)!!
            file.writeText("") // An old nonempty row is not publication authority.
            var copies = 0
            val actions = RecoveryActions({ _, _ -> RecoveryReadOutcome.NO_USABLE_MEDIA }) {}
            val message = store.withRecovery(candidate) { admitted, lease ->
                actions.save(admitted, lease) { copies++; "destination" }
            }
            assertEquals(0, copies)
            assertTrue(message.contains("No original bytes"))
            assertTrue(file.exists())
            assertTrue(store.listCandidates().isEmpty()) // No zero-byte listing expansion.
        } finally { dir.deleteRecursively() }
    }

    @Test fun healthyOpenUsesRealPreviewResultNotNativePreparationBooleanAndRechecks() = runBlocking {
        val dir = fixture()
        try {
            val store = DraftStore(dir)
            val file = store.newFile("recording", "m4a").apply { writeText("original") }
            val candidate = store.candidate(file)!!
            var health = RecoveryHealth.UNVERIFIED
            var checks = 0
            var accepted = 0
            var prepares = 0
            val actions = RecoveryActions({ _, _ -> checks++; RecoveryReadOutcome.UNVERIFIED }) { health = it }
            for (ready in listOf(true, false)) {
                val message = store.withRecovery(candidate) { admitted, lease ->
                    actions.open(admitted, lease) {
                        var result: PreviewLoad.Result? = null
                        val native = lease.withPreviewPreparation(awaitRetirement = {}) { attempted ->
                            result = PreviewLoad().load(100, { accepted++ }, {
                                com.typezero.resound.core.audio.Waveform(floatArrayOf(-.2f), floatArrayOf(.5f), 100)
                            }, { attempted(); prepares++ }, isReady = { ready })
                            true // Native preparation completed, but readiness may already be gone.
                        }
                        assertTrue(native.preparationSucceeded)
                        result
                    }
                }
                assertEquals(if (ready) RecoveryHealth.PLAYABLE else RecoveryHealth.UNVERIFIED, health)
                assertEquals(ready, message.contains("Loaded"))
                if (!ready) assertTrue(message.contains("Playback unavailable"))
            }
            assertEquals(2, checks)
            assertEquals(2, accepted)
            assertEquals(2, prepares)
            assertNotNull(store.candidate(file))
        } finally { dir.deleteRecursively() }
    }

    @Test fun cleanupUncertainReadSkipsOpenAndPublicationAndRetainsAdmission() = runBlocking {
        val root = fixture()
        try {
            for (save in listOf(false, true)) {
                val store = DraftStore(File(root, save.toString()).apply { mkdirs() })
                val file = store.newFile("recording", "m4a").apply { writeText("original") }
                val read = RecoveryMediaRead {
                    object : RecoveryMediaRead.Reader {
                        override fun inspect(file: File) = RecoveryReadOutcome.UNVERIFIED
                        override fun close() { throw java.io.IOException("uncertain close") }
                    }
                }
                var health = RecoveryHealth.PLAYABLE
                var effects = 0
                val actions = RecoveryActions(read::check) { health = it }
                val message = store.withRecovery(store.candidate(file)!!) { admitted, lease ->
                    if (save) actions.save(admitted, lease) { effects++; "destination" }
                    else actions.open(admitted, lease) { effects++; null }
                }
                assertEquals(0, effects)
                assertEquals(RecoveryHealth.UNVERIFIED, health)
                assertTrue(message.contains("cleanup is uncertain"))
                assertNull(store.candidate(file))
                assertEquals("original", file.readText())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun cancelledCheckRevokesOldPlayableEvidenceBeforeOpenOrSave() = runBlocking {
        val dir = fixture()
        try {
            val store = DraftStore(dir)
            val file = store.newFile("recording", "m4a").apply { writeText("original") }
            for (save in listOf(false, true)) {
                var health = RecoveryHealth.PLAYABLE
                var effects = 0
                val actions = RecoveryActions({ _, _ -> throw kotlinx.coroutines.CancellationException("read cancelled") }) { health = it }
                var cancelled = false
                try {
                    store.withRecovery(store.candidate(file)!!) { admitted, lease ->
                        if (save) actions.save(admitted, lease) { effects++; "destination" }
                        else actions.open(admitted, lease) { effects++; null }
                    }
                } catch (c: kotlinx.coroutines.CancellationException) { cancelled = true }
                assertTrue(cancelled)
                assertEquals(0, effects)
                assertEquals(RecoveryHealth.UNVERIFIED, health)
                assertNotNull(store.candidate(file))
                assertEquals("original", file.readText())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun saveCopiesOriginalDamagedOrUnverifiedBytesWithWarningAndDuplicateGuard() = runBlocking {
        val root = fixture()
        try {
            for (outcome in listOf(RecoveryReadOutcome.NO_USABLE_MEDIA, RecoveryReadOutcome.UNVERIFIED)) {
                val dir = File(root, outcome.name).apply { mkdirs() }
                val store = DraftStore(dir)
                val file = store.newFile("recording", "m4a").apply { writeBytes(byteArrayOf(1, 9, 0, 7)) }
                val candidate = store.candidate(file)!!
                val destination = File(root, "${outcome.name}.m4a")
                var copies = 0
                var checks = 0
                var health = RecoveryHealth.PLAYABLE
                val actions = RecoveryActions({ _, _ -> checks++; outcome }) { health = it }
                suspend fun save() = store.withRecovery(candidate) { admitted, lease ->
                    actions.save(admitted, lease) {
                        publishUnlessAlreadyPublished(store, admitted) {
                            publishAndTrack(store, admitted, { f: File -> f.toURI().toString() }) {
                                copies++
                                admitted.copyTo(destination)
                            }.name
                        }
                    }
                }
                val message = save()
                assertTrue(message.contains("Original copy saved"))
                assertTrue(message.contains(if (outcome == RecoveryReadOutcome.NO_USABLE_MEDIA) "playback unavailable" else "playback unverified"))
                assertArrayEquals(byteArrayOf(1, 9, 0, 7), file.readBytes())
                assertArrayEquals(file.readBytes(), destination.readBytes())
                assertTrue(store.isPublished(file))
                assertTrue(save().contains("Already saved"))
                assertEquals(1, copies)
                assertEquals("health cache never authorizes an action", 2, checks)
                assertNotEquals(RecoveryHealth.PLAYABLE, health)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun absentOrUnreadyPreviewNeverReportsLoaded() = runBlocking {
        val dir = fixture()
        try {
            val store = DraftStore(dir)
            val file = store.newFile("recording", "m4a").apply { writeText("original") }
            var health = RecoveryHealth.PLAYABLE
            val actions = RecoveryActions({ _, _ -> RecoveryReadOutcome.UNVERIFIED }) { health = it }
            for (result in listOf(null, PreviewLoad.Result(null, false, null))) {
                val message = store.withRecovery(store.candidate(file)!!) { admitted, lease ->
                    actions.open(admitted, lease) { result }
                }
                assertFalse("a failed preview must not claim Loaded: $message", message.contains("Loaded"))
                assertEquals(RecoveryHealth.UNVERIFIED, health)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun unusableOpenDoesNotAdoptOrPrepareAndKeepsOriginal() = runBlocking {
        val dir = fixture()
        try {
            val store = DraftStore(dir)
            val file = store.newFile("recording", "m4a").apply { writeText("incomplete-original") }
            var selected = "previous-source"
            var prepares = 0
            var checks = 0
            var health = RecoveryHealth.PLAYABLE
            val actions = RecoveryActions({ _, _ -> checks++; RecoveryReadOutcome.NO_USABLE_MEDIA }) { health = it }
            val message = store.withRecovery(store.candidate(file)!!) { admitted, lease ->
                actions.open(admitted, lease) {
                    selected = admitted.name; prepares++
                    PreviewLoad.Result(null, true, null)
                }
            }
            assertEquals("previous-source", selected)
            assertEquals(0, prepares)
            assertEquals(1, checks)
            assertEquals(RecoveryHealth.DAMAGED, health)
            assertTrue(message.contains("Appears damaged or incomplete"))
            assertEquals("incomplete-original", file.readText())
            assertNotNull(store.candidate(file))
        } finally { dir.deleteRecursively() }
    }
}
