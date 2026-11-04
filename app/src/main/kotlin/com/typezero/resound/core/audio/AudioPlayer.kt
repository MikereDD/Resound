/*
 * file:    AudioPlayer.kt
 * author:  Mike Redd (typezero)
 * version: 0.4.1
 * desc:    Lightweight MediaPlayer wrapper for in-app preview of the loaded
 *          file. Plays from a given position; the editor polls currentMs to
 *          drive the playhead and stops at the end of the selection.
 */
package com.typezero.resound.core.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri


class AudioPlayer(
    private val owner: PreparedPlayer = PreparedPlayer(),
    private val backendFactory: PlayerBackendFactory = PlayerRuntimeProvider.backendFactory(),
) {
    val isPlaying get() = owner.isPlaying
    val currentMs get() = owner.currentMs
    val failure get() = owner.failure
    fun readyFor(uri: Uri) = owner.readyFor(uri.toString())
    fun invalidate() = owner.invalidate()
    suspend fun awaitRetirement() = owner.awaitRetirement()
    suspend fun prepare(context: Context, uri: Uri) = owner.prepareBound(uri.toString()) { ticket ->
        allocatePlayerBackend(ticket, backendFactory) {
            constructOwned(MediaPlayer(), { MediaPlayerBackend(it, context, uri) }, { it.release() })
        }
    }
    fun play(uri: Uri, fromMs: Long? = null) = owner.play(uri.toString(), fromMs)
    fun seekTo(uri: Uri, ms: Long) = owner.seekTo(uri.toString(), ms)
    fun pause() = owner.pause()
    fun release() = owner.release()
}

private class MediaPlayerBackend(
    private val player: MediaPlayer, private val context: Context, private val uri: Uri,
) : PlayerBackend {
    @Volatile private var failed = false
    private var failureHandler: () -> Unit = {}
    override fun onFailure(handler: () -> Unit) { failureHandler = handler }
    override val healthy get() = !failed
    private fun healthy() { check(!failed) { "Playback unavailable" } }
    override fun prepare() {
        player.setOnErrorListener { _, _, _ -> failed = true; failureHandler(); true }
        player.setDataSource(context, uri)
        player.prepare()
        healthy()
    }
    override fun start() { healthy(); player.start() }
    override fun pause() { player.pause() }
    override fun seekTo(ms: Long) { healthy(); player.seekTo(ms.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) }
    override fun release() { player.release() }
    override val isPlaying get(): Boolean { healthy(); return player.isPlaying }
    override val currentMs get(): Long { healthy(); return player.currentPosition.toLong() }
}
