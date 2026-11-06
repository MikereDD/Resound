package com.typezero.resound.core.io

import com.typezero.resound.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputsTest {
    @Test fun destinationDisplayUsesTheSameFolderAsPublication() {
        assertEquals("Music/${Outputs.SUBDIR}", Outputs.musicDirectoryDisplay)
    }

    @Test fun destinationDisplayPreservesProductionAndDevtestIsolation() {
        val expected = if (BuildConfig.IS_DEVTEST) "Music/Resound DEV TEST" else "Music/Resound"
        assertEquals(expected, Outputs.musicDirectoryDisplay)
    }
}
