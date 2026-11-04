package com.typezero.resound.feature.timeline.draft

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors

class AsyncArrangementDraftsTest {
    @Test fun editsDuringRetryRemainSerializedAndNewestSnapshotWins() = runBlocking {
        val dir = Files.createTempDirectory("edit-during-retry").toFile()
        val retryEntered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val attempts = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maximum = java.util.concurrent.atomic.AtomicInteger()
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        val editor = CoroutineScope(coroutineContext + SupervisorJob())
        val drafts = AsyncArrangementDrafts(owner, {
            ArrangementStore(dir) { stage ->
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                try {
                    attempts += ArrangementCodec.decode(stage.readBytes()).revision
                    if (attempts.size == 1) throw java.io.IOException("first failure")
                    if (attempts.size == 2) {
                        retryEntered.complete(Unit)
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw java.io.IOException("retry failure")
                    }
                } finally { active.decrementAndGet() }
            }
        })
        try {
            drafts.load()
            editor.launch { assertTrue(drafts.offer(fixture(1))) }.join()
            withTimeout(5000) { retryEntered.await() }
            editor.launch {
                assertTrue(drafts.offer(fixture(2)))
                assertTrue(drafts.offer(fixture(9)))
            }.join()
            editor.cancel() // leaving the screen must not cancel the application-owned writer

            release.countDown(); withTimeout(5000) { drafts.finish() }
            assertEquals(listOf(1L, 1L, 9L), attempts.toList())
            assertEquals(1, maximum.get())
            assertEquals(fixture(9), ArrangementStore(dir).load())
        } finally { release.countDown(); drafts.finish(); editor.cancel(); owner.cancel(); dir.deleteRecursively() }
    }

    @Test fun staleOrDuplicateOffersCannotDisplaceNewestPendingEdit() = runBlocking {
        val dir = Files.createTempDirectory("monotonic-offers").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val attempts = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val drafts = AsyncArrangementDrafts(scope, {
            ArrangementStore(dir) { stage ->
                val revision = ArrangementCodec.decode(stage.readBytes()).revision
                attempts += revision
                if (revision == 1L) {
                    entered.complete(Unit)
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
        })
        try {
            drafts.load(); assertTrue(drafts.offer(fixture(1)))
            withTimeout(5000) { entered.await() }
            assertTrue(drafts.offer(fixture(9)))
            assertFalse("older snapshot must not replace pending revision 9", drafts.offer(fixture(8)))
            assertFalse("duplicate revision must not trigger another write", drafts.offer(fixture(9)))
            release.countDown(); drafts.finish()
            assertEquals(listOf(1L, 9L), attempts.toList())
            assertEquals(fixture(9), ArrangementStore(dir).load())
        } finally { release.countDown(); drafts.finish(); scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun failedWriteYieldsToNewestPendingStateInsteadOfRetryingOldSnapshot() = runBlocking {
        val dir = Files.createTempDirectory("retry-newest").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val attempts = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val drafts = AsyncArrangementDrafts(scope, {
            ArrangementStore(dir) { stage ->
                val revision = ArrangementCodec.decode(stage.readBytes()).revision
                attempts += revision
                if (attempts.size == 1) {
                    entered.complete(Unit)
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    throw java.io.IOException("transient publish failure")
                }
            }
        })
        try {
            drafts.load(); drafts.offer(fixture(1))
            withTimeout(5000) { entered.await() }
            for (revision in 2L..9L) assertTrue(drafts.offer(fixture(revision)))
            release.countDown(); withTimeout(5000) { drafts.finish() }
            assertEquals("failed old work must not displace queued newer edits", listOf(1L, 9L), attempts.toList())
            assertEquals(fixture(9), ArrangementStore(dir).load())
        } finally { release.countDown(); drafts.finish(); scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun transientFailureRecoversWithoutAnotherEditEvenDuringOrderlyClose() = runBlocking {
        val dir = Files.createTempDirectory("automatic-retry").toFile()
        ArrangementStore(dir).save(fixture(1))
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val drafts = AsyncArrangementDrafts(scope, {
            ArrangementStore(dir) {
                if (attempts.incrementAndGet() == 1) throw java.io.IOException("temporary failure")
            }
        })
        try {
            drafts.load()
            assertTrue(drafts.offer(fixture(2)))
            withTimeout(5000) { drafts.finish() }
            assertEquals("retry must persist the edit without another offer", fixture(2), ArrangementStore(dir).load())
            assertEquals(2, attempts.get())
        } finally { drafts.finish(); scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun newerExternalRevisionIsNotReportedAsOurSuccessfulSave() = runBlocking {
        val dir = Files.createTempDirectory("stale-ack").toFile()
        ArrangementStore(dir).save(fixture(1))
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        val outcome = CompletableDeferred<Exception?>()
        val drafts = AsyncArrangementDrafts(owner, { ArrangementStore(dir) }) { _, err -> outcome.complete(err) }
        try {
            drafts.load()
            ArrangementStore(dir).save(fixture(3))
            drafts.offer(fixture(2))
            assertNotNull("stale work must not produce a committed acknowledgement", withTimeout(5000) { outcome.await() })
            assertEquals(fixture(3), ArrangementStore(dir).load())
        } finally { drafts.finish(); owner.cancel(); dir.deleteRecursively() }
    }

    @Test fun terminatedWriterRejectsSnapshotsInsteadOfAcceptingUnconsumedWork() = runBlocking {
        val dir = Files.createTempDirectory("dead-writer").toFile()
        val owner = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + owner)
        val drafts = AsyncArrangementDrafts(scope, { ArrangementStore(dir) })
        try {
            drafts.load(); owner.cancelAndJoin()
            assertFalse("dead writer cannot claim to accept snapshots", drafts.offer(fixture()))
            assertNull(ArrangementStore(dir).load())
        } finally { drafts.finish(); scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun cannotWriteBeforeInitialRecoveryCompletes() = runBlocking {
        val dir = Files.createTempDirectory("gate").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val drafts = AsyncArrangementDrafts(scope, { ArrangementStore(dir) })
        try {
            assertFalse(drafts.offer(fixture()))
            assertNull(drafts.load())
            assertTrue(drafts.offer(fixture()))
            drafts.finish()
            assertEquals(fixture(), ArrangementStore(dir).load())
        } finally { drafts.finish(); scope.cancel(); dir.deleteRecursively() }
    }
    @Test fun corruptRecoveryCannotBeOverwrittenByBlankAutosave() = runBlocking {
        val dir = Files.createTempDirectory("gate").toFile()
        val file = java.io.File(dir, "active-a.draft").also { it.writeText("original") }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val drafts = AsyncArrangementDrafts(scope, { ArrangementStore(dir) })
        try {
            try { drafts.load(); fail() } catch (_: CorruptArrangement) { }
            assertFalse(drafts.offer(ArrangementDraft(99)))
            assertEquals("original", file.readText())
        } finally { drafts.finish(); scope.cancel(); dir.deleteRecursively() }
    }
    @Test fun blockedOldWriteConflatesToNewestPendingSnapshotWithoutBlockingCaller() = runBlocking {
        val dir = Files.createTempDirectory("conflate").toFile()
        val started = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val committed = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val drafts = AsyncArrangementDrafts(scope, {
            ArrangementStore(dir) { stage ->
                if (ArrangementCodec.decode(stage.readBytes()).revision == 1L) {
                    started.complete(Unit)
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
        }) { rev, err -> if (err == null) committed += rev else throw err }
        try {
            drafts.load(); drafts.offer(fixture(1)); withTimeout(5000) { started.await() }
            for (rev in 2L..9L) assertTrue(drafts.offer(fixture(rev)))
            release.countDown(); drafts.finish()
            assertEquals(listOf(1L, 9L), committed.toList())
            assertEquals(fixture(9), ArrangementStore(dir).load())
        } finally { release.countDown(); drafts.finish(); scope.cancel(); dir.deleteRecursively() }
    }
    @Test fun failedPublishReportsErrorPreservesOldRevisionAndLaterEditCanRetry() = runBlocking {
        val dir = Files.createTempDirectory("retry").toFile()
        ArrangementStore(dir).save(fixture(1))
        val failed = CompletableDeferred<Exception>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val drafts = AsyncArrangementDrafts(scope, {
            ArrangementStore(dir) { stage ->
                if (ArrangementCodec.decode(stage.readBytes()).revision == 2L) throw java.io.IOException("no space")
            }
        }) { _, err -> if (err != null) failed.complete(err) }
        try {
            assertEquals(fixture(1), drafts.load())
            drafts.offer(fixture(2)); assertTrue(withTimeout(5000) { failed.await() } is java.io.IOException)
            assertEquals(fixture(1), ArrangementStore(dir).load())
            drafts.offer(fixture(3)); drafts.finish()
            assertEquals(fixture(3), ArrangementStore(dir).load())
        } finally { drafts.finish(); scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun saveRunsOffCallerAndFreshOwnerRecoversLastCommittedDraft() = runBlocking {
        val dir = Files.createTempDirectory("async-draft").toFile()
        val executor = Executors.newSingleThreadExecutor { Thread(it, "draft-io") }
        val io = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var factoryThread = ""
        val saved = CompletableDeferred<Long>()
        val drafts = AsyncArrangementDrafts(scope, {
            factoryThread = Thread.currentThread().name; ArrangementStore(dir)
        }, io) { revision, failure ->
            if (failure == null) saved.complete(revision) else saved.completeExceptionally(failure)
        }
        try {
            assertNull(drafts.load())
            assertTrue(drafts.offer(fixture()))
            assertEquals(1L, withTimeout(5000) { saved.await() })
            assertTrue(factoryThread, factoryThread.startsWith("draft-io"))
            assertEquals(fixture(), ArrangementStore(dir).load())
        } finally { drafts.finish(); scope.cancel(); io.close(); dir.deleteRecursively() }
    }
}
