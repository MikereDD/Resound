package com.typezero.resound.core.io

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ScratchSweeperTest {
    @Before fun reset() = ScratchSweeper.resetForTest()
    private fun cache(): File = java.nio.file.Files.createTempDirectory("cache").toFile()

    @Test fun startupSweepDeletesPriorProcessScratchOnlyAndNeverDrafts() {
        val root = cache(); val files = java.nio.file.Files.createTempDirectory("files").toFile()
        val src = File(root, "src_a.mp3").also { it.writeText("x") }       // fresh timestamp: age is irrelevant now
        val other = File(root, "keep.txt").also { it.writeText("x") }
        File(root, "out").mkdirs(); val out = File(root, "out/m_1.m4a").also { it.writeText("x") }
        val draft = File(files, "drafts/recording_1.m4a").also { it.parentFile.mkdirs(); it.writeText("x") }
        assertEquals(2, ScratchSweeper.sweepAtStartup(root))
        assertFalse(src.exists() || out.exists()); assertTrue(other.exists() && draft.exists())
    }
    @Test fun refusesOnceAnyOperationExistsSoActiveFilesAreNeverCandidates() {
        val root = cache(); val active = File(root, "src_live.mp3").also { it.writeText("x") }
        val op = OperationFiles(java.util.concurrent.Executor { it.run() }); op.adopt(active)
        assertEquals(-1, ScratchSweeper.sweepAtStartup(root)); assertTrue(active.exists())
    }
    @Test fun runsAtMostOncePerProcess() {
        val root = cache(); assertEquals(0, ScratchSweeper.sweepAtStartup(root))
        val late = File(root, "src_late.mp3").also { it.writeText("x") }
        assertEquals(-1, ScratchSweeper.sweepAtStartup(root)); assertTrue(late.exists())
    }
}
