package com.typezero.resound.core.io

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PublicationNamingTest {
    private val request = NamingRequest("Take_\$DATE_\$TIME_\$DURATION_\$FORMAT", Instant.parse("2026-11-05T13:14:15Z"), ZoneId.of("UTC"))

    @Test fun resolvesActualOutputMetadataWithFrozenTime() {
        assertEquals("Take_20261105_131415_00-02-03.456.flac", // trailing $FORMAT is not repeated
            PublicationNaming.resolve(request, "flac", 123456L).name)
    }

    @Test fun literalStemKeepsOneCaseInsensitiveOutputExtension() {
        assertEquals("Session.M4A", PublicationNaming.resolve(request.copy(pattern = "Session.M4A"), "m4a").name)
        assertEquals("Session.wav", PublicationNaming.resolve(request.copy(pattern = "Session.mp3"), "wav").name)
    }

    @Test fun automaticAndUnavailableValuesNeverInventNames() {
        assertNull(PublicationNaming.resolve(request.copy(pattern = ""), "m4a").name)
        assertNull(PublicationNaming.resolve(request, "m4a").name)
        assertTrue(PublicationNaming.resolve(request, "m4a").notice!!.contains("DURATION"))
        assertNull(PublicationNaming.resolve(request.copy(pattern = "\$TRIM"), "m4a").name)
    }

    @Test fun rejectsUnsafeOrUnknownPatternsWithoutBlockingAutomaticFallback() {
        listOf("../take", "a/b", "a\\b", "a\n", "..", "\$UNKNOWN", "a".repeat(181)).forEach {
            val result = PublicationNaming.resolve(request.copy(pattern = it), "m4a")
            assertNull(it, result.name)
            assertNotNull(it, result.notice)
        }
    }

    @Test fun trimOnlyResolvesRealRange() {
        val trim = request.copy(pattern = "\$TRIM")
        assertEquals("00-00-01.000--00-00-03.000.m4a", PublicationNaming.resolve(trim, "m4a", trimRange = 1000L to 3000L).name)
        assertNull(PublicationNaming.resolve(trim, "m4a", trimRange = 3000L to 1000L).name)
    }
}
