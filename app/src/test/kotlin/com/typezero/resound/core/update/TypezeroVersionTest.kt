package com.typezero.resound.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Typezer∅ version comparison standard: canonical vectors and malformed input. */
class TypezeroVersionTest {
    private fun cmp(installed: String, candidate: String) =
        Integer.signum(TypezeroVersion.parse(candidate).compareTo(TypezeroVersion.parse(installed)))

    /** examples/version-comparison-vectors.json at standards commit 90f2720a, plus Resound's own line. */
    @Test fun canonicalVectors() {
        listOf(
            Triple("1.3-dev.6.3", "1.3-dev.6.4", 1),
            Triple("1.3-dev.6.4", "1.3-dev.6.3", -1),
            Triple("1.3-dev.9", "1.3-dev.10", 1),
            Triple("1.3-dev.6.4", "1.3-dev.6.4", 0),
            Triple("1.3-dev.6.4", "1.3", 1),
            Triple("1.3", "1.3-dev.99", -1),
            Triple("1.9", "1.10", 1),
            Triple("2.7-dev.10.9", "2.7-dev.10.9.1", 1),
            Triple("1.0.0", "1.0", 0),
            Triple("1.0-dev.1.0", "1.0-dev.1", 0),
            Triple("0.8.0-dev.9", "0.8.0-dev.10", 1),
            Triple("0.8.0-dev.11", "0.8.0-dev.12", 1),
            Triple("0.8.0-dev.11", "0.8.0-dev.11", 0),
            Triple("0.8.0-dev.11", "0.8.0-dev.10", -1),
        ).forEach { (installed, candidate, expected) ->
            assertEquals("$installed -> $candidate", expected, cmp(installed, candidate))
        }
    }

    @Test fun developmentFlag() {
        assertTrue(TypezeroVersion.parse("0.8.0-dev.11").isDevelopment)
        assertFalse(TypezeroVersion.parse("0.8.0").isDevelopment)
    }

    @Test fun malformedVersionsAreRejectedNotGuessed() {
        listOf(
            "", "1", "v1.0", "1.0-dev", "1.0-dev.", "1.0-beta.1", "1.0-dev.x", "1..0", "1.0.", " 1.0",
            "1.0 ", "1.0-DEV.1", "1.0-dev.1-dev.2", "1.0+build.5", "-1.0", "1.0-dev.-1",
            "1.99999999999999999999", "1.0-dev." + "1".repeat(100),
        ).forEach { value ->
            try {
                TypezeroVersion.parse(value)
                fail("accepted malformed version '$value'")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }
}
