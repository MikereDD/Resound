package com.typezero.resound.feature.edit

/** Typezer∅: Last-check evidence, never action authority or a full-file integrity claim. */
enum class RecoveryHealth(val label: String) {
    PLAYABLE("Playable preview — last check"),
    DAMAGED("Appears damaged or incomplete — last check"),
    UNVERIFIED("Unverified — Open to check");

    companion object {
        fun fromPreview(result: PreviewLoad.Result?): RecoveryHealth =
            if (result?.ready == true && result.waveform?.provenance ==
                com.typezero.resound.core.audio.WaveformProvenance.REAL &&
                result.waveform.bucketCount > 0) PLAYABLE else UNVERIFIED
    }
}
