package com.typezero.resound.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Saved $FORMAT patterns keep working without repeating the extension (dev.10 Pixel finding). */
class FilenameExtensionTest {
    private fun name(pattern: String, ext: String = "m4a", durationMs: Long? = 65_000L) = PublicationNaming.resolve(
        NamingRequest(pattern, Instant.parse("2026-11-05T20:55:50Z"), ZoneId.of("UTC")), ext, durationMs).name

    @Test fun pixelPatternNoLongerRepeatsTheFormat() {
        assertEquals("test_205550_20261105_00-01-05.000.m4a", name("test_\$TIME_\$DATE_\$DURATION_\$FORMAT"))
    }

    @Test fun trailingFormatFollowsTheActualOutputExtension() {
        assertEquals("take.mp3", name("take_\$FORMAT", "mp3"))
        assertEquals("take.wav", name("take-\$FORMAT", "wav"))
        assertEquals("take.m4a", name("take \$FORMAT"))
        assertEquals("take.m4a", name("take\$FORMAT"))
        assertEquals("take.m4a", name("take_\$FORMAT.m4a"))
        assertEquals("take.m4a", name("take_\$FORMAT_\$FORMAT"))
    }

    @Test fun formatElsewhereInTheNameIsKept() {
        assertEquals("m4a_take.m4a", name("\$FORMAT_take"))
        assertEquals("take_m4a_20261105.m4a", name("take_\$FORMAT_\$DATE"))
    }

    @Test fun formatAloneUsesAutomaticNamingWithAReason() {
        val result = PublicationNaming.resolve(NamingRequest("\$FORMAT"), "m4a")
        assertNull(result.name)
        assertTrue(result.notice!!.contains("extension"))
    }

    @Test fun plainNamesAndTypedExtensionsAreUnchanged() {
        assertEquals("test.m4a", name("test"))
        assertEquals("test.m4a", name("test.m4a"))
        assertEquals("Session.wav", name("Session.mp3", "wav"))
        assertEquals("format_notes.m4a", name("format_notes"))
    }
}
