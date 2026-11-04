package com.typezero.resound.core.audio

/** Variant policy defaults to unmodified playback. */
object PlayerRuntimeProvider {
    fun backendFactory(): PlayerBackendFactory = PlayerBackendFactory { { it } }
    fun playerFactory(): AudioPlayerFactory = AudioPlayerFactory {
        AudioPlayer(backendFactory = backendFactory())
    }
}
