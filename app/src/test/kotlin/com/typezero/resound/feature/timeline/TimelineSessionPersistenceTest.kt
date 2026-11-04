package com.typezero.resound.feature.timeline

import com.typezero.resound.feature.timeline.draft.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TimelineSessionPersistenceTest {
    @Test fun equalPropertyAssignmentsDoNotCreateAnotherDraftRevision() = runBlocking {
        val dir = Files.createTempDirectory("unchanged-session").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = TimelineSession()
        try {
            session.startPersistence(scope, { ArrangementStore(dir) }, Dispatchers.IO) { emptyList() }.join()
            session.tracks = session.tracks.toList()
            session.idCounter = session.idCounter
            session.selectedClip = session.selectedClip
            session.pxPerSec = session.pxPerSec
            session.finishPersistence()
            assertNull("unchanged properties must not write an automatic draft", ArrangementStore(dir).load())
        } finally { session.finishPersistence(); scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun exhaustedRetriesKeepUnsavedSessionAndClearlyReportFailure() = runBlocking {
        val dir = Files.createTempDirectory("exhausted-session").toFile()
        val previous = ArrangementDraft(1, emptyList(), 2)
        ArrangementStore(dir).save(previous)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = TimelineSession()
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        try {
            session.startPersistence(scope, {
                ArrangementStore(dir) { attempts.incrementAndGet(); throw java.io.IOException("no space") }
            }, Dispatchers.IO) { emptyList() }.join()
            session.tracks = listOf(Track(1, "unsaved edit"))
            withTimeout(5000) { session.finishPersistence() }
            assertEquals(4, attempts.get())
            assertEquals("unsaved edit", session.tracks.single().name)
            assertEquals(previous, ArrangementStore(dir).load())
            assertTrue(session.persistenceError.orEmpty(), session.persistenceError.orEmpty().contains("not saved"))
            assertTrue(session.draftStatus, session.draftStatus.contains("not saved"))
        } finally { session.finishPersistence(); scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun recoveryBlocksEditingUntilHydratedAndNeverSavesInitialEmptyState() = runBlocking {
        val dir = Files.createTempDirectory("hydrate").toFile()
        val stored = ArrangementDraft(10, listOf(DraftTrack(1, "retained", emptyList(), true, 0.3)), 2, null, 80f)
        ArrangementStore(dir).save(stored)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        val s = TimelineSession()
        try {
            val loading = s.startPersistence(scope, { ArrangementStore(dir) }, Dispatchers.IO) { d ->
                entered.complete(Unit); proceed.await()
                d.tracks.map { Track(it.id, it.name, muted = it.muted, volume = it.volume) }
            }
            assertFalse(s.recoveryReady)
            withTimeout(5000) { entered.await() }
            assertEquals(stored, ArrangementStore(dir).load())
            proceed.complete(Unit); loading.join(); s.finishPersistence()
            assertTrue(s.recoveryReady); assertEquals(stored, ArrangementStore(dir).load())
            assertEquals("retained", s.tracks.single().name); assertEquals(80f, s.pxPerSec, 0f)
        } finally { proceed.complete(Unit); s.finishPersistence(); scope.cancel(); dir.deleteRecursively() }
    }
    @Test fun unreadableDraftLeavesFilesIntactAndEditingDisabled() = runBlocking {
        val dir = Files.createTempDirectory("blocked").toFile()
        val file = java.io.File(dir, "active-a.draft").also { it.writeText("original") }
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val s = TimelineSession()
        try {
            s.startPersistence(scope, { ArrangementStore(dir) }, Dispatchers.IO) { emptyList() }.join()
            assertFalse(s.recoveryReady); assertNotNull(s.persistenceError)
            s.finishPersistence(); assertEquals("original", file.readText())
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun actualSessionChangesAreRecoveredByNewSessionRatherThanNewEmptyState() = runBlocking {
        val dir = Files.createTempDirectory("session-draft").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val factory = { ArrangementStore(dir) }
        val hydrate: suspend (ArrangementDraft) -> List<Track> = { d ->
            d.tracks.map { Track(it.id, it.name, muted = it.muted, volume = it.volume) }
        }
        val first = TimelineSession()
        val second = TimelineSession()
        try {
            first.startPersistence(scope, factory, Dispatchers.IO, hydrate).join()
            first.tracks = listOf(Track(first.nextId(), "A", muted = true, volume = 0.5),
                Track(first.nextId(), "B", volume = 1.25))
            first.pxPerSec = 77f
            first.finishPersistence()
            assertNotNull("a real session mutation must create metadata", ArrangementStore(dir).load())
            second.startPersistence(scope, factory, Dispatchers.IO, hydrate).join()
            assertEquals(first.tracks, second.tracks)
            assertEquals(77f, second.pxPerSec, 0f)
            assertEquals(3L, second.nextId())
        } finally {
            first.finishPersistence(); second.finishPersistence(); scope.cancel(); dir.deleteRecursively()
        }
    }
}
