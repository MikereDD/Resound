package com.typezero.resound.core.io

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NamingIntegrationTest {
    @Test fun metadataIsReadOnlyForDurationAndFailureFallsBackNonfatally() {
        var reads = 0
        val automatic = PublicationNaming.atCreation(NamingRequest(), "m4a", { reads++; error("unreadable") })
        assertNull(automatic.name)
        assertEquals("literal.wav", PublicationNaming.atCreation(NamingRequest("literal"), "wav", { reads++; null }).name)
        assertEquals(0, reads)
        val failed = PublicationNaming.atCreation(NamingRequest("\$DURATION"), "m4a", { reads++; error("unreadable") })
        assertNull(failed.name)
        assertNotNull(failed.notice)
        assertEquals(1, reads)
    }

    @Test fun delayedStartupCannotReplaceUserChoiceAndWritesSurviveRestartInOrder() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var disk = NamingPatterns(recording = "old", exports = "old-export")
        val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = NamingPreferenceState(app, read = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); disk
        }, write = { disk = it })
        try {
            val startup = async(start = CoroutineStart.UNDISPATCHED) { store.initialize() }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val frozen = NamingRequest(store.state.value.recording)
            store.update(recording = "new")
            store.update(exports = "export")
            store.update(recording = "last")
            assertEquals("", frozen.pattern) // Record remains available before preferences load.
            assertEquals("last", store.state.value.recording)
            release.countDown()
            startup.await()
            withTimeout(5000) { store.flush() }
            assertEquals(NamingPatterns("last", "export"), disk)
            val restarted = NamingPreferenceState(app, { disk }, { disk = it })
            restarted.initialize()
            assertEquals(disk, restarted.state.value)
        } finally { release.countDown(); app.cancel() }
    }
}
