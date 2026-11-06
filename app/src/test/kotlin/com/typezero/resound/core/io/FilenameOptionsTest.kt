package com.typezero.resound.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Filename option checkboxes edit the existing token pattern predictably (dev.10 naming polish). */
class FilenameOptionsTest {
    private fun on(p: String, o: FilenameOption) = FilenameOptions.toggle(p, o, true)
    private fun off(p: String, o: FilenameOption) = FilenameOptions.toggle(p, o, false)

    @Test fun addingAppendsOnceAfterTypedNameWithSeparator() {
        assertEquals("test_\$DATE", on("test", FilenameOption.DATE))
        assertEquals("test_\$DATE", on("test_\$DATE", FilenameOption.DATE)) // no duplicate
        assertEquals("test-\$TIME", on("test-", FilenameOption.TIME))
        assertEquals("\$DURATION", on("", FilenameOption.DURATION))
        assertEquals("test_\$DATE_\$TIME", on(on("test", FilenameOption.DATE), FilenameOption.TIME))
    }

    @Test fun removingKeepsManualTextAndOtherTokens() {
        assertEquals("test_\$TIME", off("test_\$DATE_\$TIME", FilenameOption.DATE))
        assertEquals("test", off("\$DATE_test", FilenameOption.DATE))
        assertEquals("my take", off("my take \$DURATION", FilenameOption.DURATION))
        assertEquals("", off("\$DATE", FilenameOption.DATE)) // back to automatic naming
        assertEquals("a_b", off("a_\$TIME_b_\$TIME", FilenameOption.TIME))
        assertEquals("plain", off("plain", FilenameOption.DATE))
    }

    @Test fun checkboxesFollowManuallyTypedTokensWithoutMatchingLongerWords() {
        assertTrue(FilenameOptions.isSelected("x \$DATE y", FilenameOption.DATE))
        assertFalse(FilenameOptions.isSelected("x \$DATES", FilenameOption.DATE))
        assertFalse(FilenameOptions.isSelected("DATE", FilenameOption.DATE))
        assertEquals("x \$DATES", off("x \$DATES", FilenameOption.DATE))
    }

    @Test fun formatIsNotAnOptionBecauseTheExtensionAlreadyShowsIt() {
        assertEquals(listOf("Date", "Time", "Duration", "Trim range"), FilenameOption.entries.map { it.label })
    }

    @Test fun toggledPatternsResolveThroughTheExistingNamingEngine() {
        val request = NamingRequest(on(on("test", FilenameOption.DATE), FilenameOption.DURATION),
            Instant.parse("2026-11-05T18:30:15Z"), ZoneId.of("UTC"))
        assertEquals("test_20261105_00-01-05.000.m4a",
            PublicationNaming.resolve(request, "m4a", durationMs = FilenameOptions.SAMPLE_DURATION_MS).name)
        val trim = NamingRequest(on("clip", FilenameOption.TRIM))
        assertEquals("clip_00-00-01.000--00-00-06.000.mp3",
            PublicationNaming.resolve(trim, "mp3", trimRange = FilenameOptions.SAMPLE_TRIM_RANGE).name)
    }
}
