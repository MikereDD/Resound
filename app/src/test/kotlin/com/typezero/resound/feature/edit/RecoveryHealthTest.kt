package com.typezero.resound.feature.edit

import com.typezero.resound.core.audio.Waveform
import org.junit.Assert.*
import org.junit.Test

class RecoveryHealthTest {
    @Test fun playableRequiresReadyPreviewWithRealDecodedWaveform() {
        val real = Waveform(floatArrayOf(-.2f), floatArrayOf(.5f), 100)
        assertEquals(RecoveryHealth.UNVERIFIED, RecoveryHealth.fromPreview(PreviewLoad.Result(Waveform.unavailable(100), true, "Waveform unavailable")))
        assertEquals(RecoveryHealth.PLAYABLE, RecoveryHealth.fromPreview(PreviewLoad.Result(real, true, null)))
    }
}
