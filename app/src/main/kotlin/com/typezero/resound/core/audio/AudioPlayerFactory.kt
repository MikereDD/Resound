package com.typezero.resound.core.audio

/** A factory, not a shared player lifetime. Each editor owns its returned player. */
fun interface AudioPlayerFactory { fun create(): AudioPlayer }
