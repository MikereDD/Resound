package com.typezero.resound.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Staged update files are removed after a cancelled install, a failed download or a restart. */
class UpdateStagingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun stagingLivesInCacheUpdates() {
        val cache = tmp.newFolder("cache")
        assertEquals(File(cache, "updates"), UpdateStaging.dir(cache))
    }

    @Test fun cancelledInstallLeavesNoStagedFiles() {
        val dir = UpdateStaging.dir(tmp.newFolder("cache")).apply { mkdirs() }
        val apk = File(dir, "Resound-v0.8.0-dev.12.apk").apply { writeBytes(ByteArray(1024)) }
        val signature = File(dir, "Resound-v0.8.0-dev.12.apk.sig").apply { writeBytes(ByteArray(512)) }
        // The user backs out of Android's installer and closes the update dialog.
        assertEquals(2, UpdateStaging.clear(dir))
        assertFalse(apk.exists() || signature.exists())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun clearingIsSafeWhenNothingIsStaged() {
        val cache = tmp.newFolder("cache")
        assertEquals(0, UpdateStaging.clear(UpdateStaging.dir(cache)))
        assertEquals(0, UpdateStaging.clear(UpdateStaging.dir(cache).apply { mkdirs() }))
    }

    @Test fun onlyStagedFilesAreTouched() {
        val cache = tmp.newFolder("cache")
        val outside = File(cache, "src_123.mp3").apply { writeText("working copy") }
        val dir = UpdateStaging.dir(cache).apply { mkdirs() }
        File(dir, "partial.apk").writeText("x")
        assertEquals(1, UpdateStaging.clear(dir))
        assertTrue(outside.exists())
    }
}
