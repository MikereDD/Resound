/*
 * file:    LatestOnlySnackbarTest.kt
 * author:  Typezer∅
 * desc:    Behavioural guard for Export Mix feedback, run against the real Material 3 SnackbarHostState on a
 *          manually stepped dispatcher: newer outcomes supersede visible and queued ones, a burst shows only the
 *          last message, dismissal and screen exit leave nothing behind.
 */
package com.typezero.resound.feature.timeline

import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class LatestOnlySnackbarTest {
    /** Runs dispatched work only when [runCurrent] is called, so interleavings are deterministic. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runCurrent() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private val dispatcher = ManualDispatcher()
    private val scope = CoroutineScope(Job() + dispatcher)
    private val feedback = LatestOnlySnackbar(scope)
    private val shown = mutableListOf<String>()

    private fun visible(): String? = feedback.hostState.currentSnackbarData?.visuals?.message

    /** Lets every pending coroutine run, recording each distinct message that became visible. */
    private fun step() {
        dispatcher.runCurrent()
        visible()?.let { if (shown.lastOrNull() != it) shown += it }
    }

    private fun dismissVisible() { feedback.hostState.currentSnackbarData!!.dismiss(); step() }

    /** Drains the queue repeatedly; nothing else may ever appear. */
    private fun assertNothingFurtherAppears() {
        repeat(5) { step(); assertNull("no stale message after the newest", visible()) }
    }

    @Test fun newerOutcomesSupersedeVisibleAndQueuedOnes() {
        feedback.announce("A"); step()
        assertEquals("A", visible())
        feedback.announce("B"); feedback.announce("C"); step()
        assertEquals("C", visible())
        dismissVisible()
        assertNothingFurtherAppears()
        assertEquals(listOf("A", "C"), shown)
    }

    @Test fun burstBeforeAnyDispatchShowsOnlyTheLast() {
        repeat(10) { feedback.announce("outcome $it") }
        step()
        assertEquals("outcome 9", visible())
        dismissVisible()
        assertNothingFurtherAppears()
        assertEquals(listOf("outcome 9"), shown)
    }

    @Test fun repeatedAttemptsReplaceTheVisibleOutcome() {
        feedback.announce("Mix blocked 1"); step()
        feedback.announce("Mix blocked 2"); step()
        feedback.announce("Mix blocked 3"); step()
        assertEquals("Mix blocked 3", visible())
        dismissVisible()
        assertNothingFurtherAppears()
        assertEquals(listOf("Mix blocked 1", "Mix blocked 2", "Mix blocked 3"), shown)
    }

    @Test fun userDismissalClearsItAndTheNextOutcomeStillShows() {
        feedback.announce("A"); step()
        dismissVisible()
        assertNull(visible())
        feedback.announce("B"); step()
        assertEquals("B", visible())
        assertEquals(listOf("A", "B"), shown)
    }

    @Test fun leavingTheScreenRemovesVisibleAndPendingMessages() {
        feedback.announce("A"); step()
        feedback.announce("B")              // pending, not yet dispatched
        scope.cancel()                      // what rememberCoroutineScope does when the screen leaves composition
        step()
        assertNothingFurtherAppears()
        feedback.announce("after exit"); step()
        assertNull("a cancelled screen scope shows nothing", visible())
        assertEquals(listOf("A"), shown)
    }

    @Test fun messagesKeepTheDismissActionAndReadableDuration() {
        feedback.announce("Mix saved"); step()
        val visuals = feedback.hostState.currentSnackbarData!!.visuals
        assertTrue(visuals.withDismissAction)
        assertEquals(SnackbarDuration.Long, visuals.duration)
    }
}
