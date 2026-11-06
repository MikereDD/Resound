package com.typezero.resound.core.audio

import org.junit.Assert.*
import org.junit.Test

class DecodeResourcesTest {
    @Test fun configureAndStartFailureAlwaysReleaseCreatedCodec() {
        for (stage in listOf("configure", "start")) {
            val events = mutableListOf<String>()
            val original = IllegalStateException(stage)
            try {
                DecodeResources().use { resources ->
                    resources.codec({ "codec" }, { events += "configure"; if (stage == "configure") throw original },
                        { events += "start"; throw original }, { events += "stop"; error("stop") }, { events += "release" })
                }
                fail("expected failure")
            } catch (t: IllegalStateException) { assertSame(original, t) }
            assertTrue("$stage must release the acquired codec", events.contains("release"))
            assertEquals(1, events.count { it == "release" })
        }
    }
    @Test fun allCleanupRunsAndPrimaryCancellationWins() {
        val resources = DecodeResources(); val events = mutableListOf<String>()
        resources.defer { events += "descriptor" }
        resources.defer { events += "extractor"; error("extractor release") }
        resources.defer { events += "codec release"; error("codec release") }
        resources.defer { events += "codec stop"; error("codec stop") }
        val primary = kotlinx.coroutines.CancellationException("cancel")
        try { resources.close(primary) } catch (t: Throwable) { fail("cleanup masked primary: $t") }
        assertEquals(listOf("codec stop", "codec release", "extractor", "descriptor"), events)
        assertEquals(3, primary.suppressed.size)
    }
    @Test fun noProgressIsBounded() {
        var clock = 0L; val budget = DecodeBudget({ clock }, 5, 100)
        clock = 6
        try { budget.check(); fail("unbounded wait") } catch (_: IllegalStateException) {}
    }
    @Test fun endlessProgressStillHasOverallBound() {
        var clock = 0L; val budget = DecodeBudget({ clock }, 5, 10)
        for (t in 1L..9L) { clock = t; budget.check(true) }
        clock = 11
        try { budget.check(true); fail("unbounded drain") } catch (_: IllegalStateException) {}
    }
}
