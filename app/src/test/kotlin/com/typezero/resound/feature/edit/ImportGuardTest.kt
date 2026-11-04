package com.typezero.resound.feature.edit

import org.junit.Assert.*
import org.junit.Test

class ImportGuardTest {
    private class EditorState { var current: String? = "SENTINEL"; var sel = 777L; var playerInits = 0 }

    /** Mirrors the loadInto adoption boundary using the real guard. */
    private fun load(st: EditorState, durationMs: Long, name: String): Boolean {
        val adopted = adoptIfValid(durationMs) { st.current = name; st.sel = durationMs; st.playerInits++ }
        return adopted != null
    }

    @Test fun zeroIsRejectedAndPriorStateAndPlayerUntouched() {
        val st = EditorState()
        assertFalse(load(st, 0, "dt_0ms"))
        assertEquals("SENTINEL", st.current); assertEquals(777L, st.sel); assertEquals(0, st.playerInits)
    }
    @Test fun negativeUnknownIsRejectedToo() {
        val st = EditorState(); assertFalse(load(st, -1, "x")); assertEquals("SENTINEL", st.current)
    }
    @Test fun positiveBoundariesAreAdopted() {
        for (ms in listOf(1L, 100L, 199L, 200L, 201L)) {
            val st = EditorState()
            assertTrue("ms=$ms", load(st, ms, "dt_${ms}ms"))
            assertEquals("dt_${ms}ms", st.current); assertEquals(ms, st.sel); assertEquals(1, st.playerInits)
        }
    }
    @Test fun messageIsNonEmpty() = assertTrue(EMPTY_AUDIO_MESSAGE.isNotBlank())
}
