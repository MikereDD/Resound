package com.typezero.resound.feature.timeline.draft

import org.junit.Assert.*
import org.junit.Test

class ArrangementRecoveryTest {
    @Test fun deniedAccessDoesNotDropOrChangeReferences() {
        val d = fixture()
        val r = ArrangementRecovery.inspect(d) { throw SecurityException("revoked") }
        assertEquals(setOf(10L, 11L), r.missingClipIds); assertEquals(d, r.draft)
    }
    @Test fun availableReferencesHaveNoMissingFlags() {
        assertTrue(ArrangementRecovery.inspect(fixture()) { true }.missingClipIds.isEmpty())
    }
    @Test fun cancellationIsNotMisreportedAsAMissingSource() {
        try { ArrangementRecovery.inspect(fixture()) { throw kotlinx.coroutines.CancellationException("cancel") }; fail() }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun missingSourcesAreRetainedWithExactTimingAndOrder() {
        val d = fixture()
        val recovered = ArrangementRecovery.inspect(d) { it.uri.endsWith("one") }
        assertEquals(setOf(11L), recovered.missingClipIds)
        assertEquals(d, recovered.draft) // no clip or audio metadata is dropped/rewritten
    }
}
