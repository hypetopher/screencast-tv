package com.screencast.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

class VideoPlayerManager(context: Context) {

    companion object {
        private const val TAG = "VideoPlayerManager"
    }

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = mutableListOf<PlayerCallback>()
    private var positionPoller: Runnable? = null

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "Playback state changed: $playbackState")
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (exoPlayer.playWhenReady) {
                            notifyPlaying()
                            startPositionPolling()
                        } else {
                            notifyPaused()
                        }
                    }
                    Player.STATE_ENDED -> {
                        stopPositionPolling()
                        notifyStopped()
                    }
                    Player.STATE_IDLE -> {
                        stopPositionPolling()
                    }
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "isPlaying changed: $isPlaying")
                if (isPlaying) {
                    notifyPlaying()
                    startPositionPolling()
                } else if (exoPlayer.playbackState == Player.STATE_READY) {
                    notifyPaused()
                    stopPositionPolling()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.errorCodeName} - ${error.message}", error)
                stopPositionPolling()
                callbacks.forEach { it.onError("${error.errorCodeName}: ${error.message}") }
            }
        })
    }

    fun addCallback(callback: PlayerCallback) {
        callbacks.add(callback)
    }

    fun removeCallback(callback: PlayerCallback) {
        callbacks.remove(callback)
    }

    fun playUrl(url: String, startPositionMs: Long = 0) {
        handler.post {
            try {
                Log.d(TAG, "playUrl: $url, startPos: $startPositionMs")

                val uri = Uri.parse(url)
                val mediaSource = buildMediaSource(uri, url)

                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                if (startPositionMs > 0) {
                    exoPlayer.seekTo(startPositionMs)
                }
                exoPlayer.playWhenReady = true
                Log.d(TAG, "Playback started")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting playback", e)
                callbacks.forEach { it.onError(e.message ?: "Failed to start playback") }
            }
        }
    }

    private fun buildMediaSource(uri: Uri, url: String): MediaSource {
        // Build HTTP data source with custom headers for CDN compatibility
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)

        // Build MediaItem with headers for CDN compatibility
        val headers = mutableMapOf<String, String>()
        val host = uri.host ?: ""
        if (host.contains("aliyundrive") || host.contains("alicdn") || host.contains("aliyuncs") || host.contains("alipan") || host.contains("pdsapi")) {
            // Aliyun Drive CDN - use their app as referer
            headers["Referer"] = "https://www.alipan.com/"
        }

        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        if (headers.isNotEmpty()) {
            mediaItemBuilder.setRequestMetadata(
                MediaItem.RequestMetadata.Builder().build()
            )
            httpDataSourceFactory.setDefaultRequestProperties(headers)
            Log.d(TAG, "Using custom headers: $headers")
        }
        val mediaItem = mediaItemBuilder.build()

        // Choose source type based on URL
        val isHls = url.contains(".m3u8", ignoreCase = true) ||
                url.contains("/m3u8", ignoreCase = true) ||
                url.contains("m3u8?", ignoreCase = true) ||
                url.contains("/hls/", ignoreCase = true)
        Log.d(TAG, "URL type detection: isHls=$isHls, url=${url.take(100)}...")
        return when {
            isHls -> {
                Log.d(TAG, "Using HLS media source")
                HlsMediaSource.Factory(httpDataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem)
            }
            url.contains(".mpd", ignoreCase = true) -> {
                Log.d(TAG, "Using DASH media source")
                androidx.media3.exoplayer.dash.DashMediaSource.Factory(httpDataSourceFactory)
                    .createMediaSource(mediaItem)
            }
            else -> {
                Log.d(TAG, "Using progressive media source")
                ProgressiveMediaSource.Factory(httpDataSourceFactory)
                    .createMediaSource(mediaItem)
            }
        }
    }

    fun play() {
        handler.post { exoPlayer.playWhenReady = true }
    }

    fun pause() {
        handler.post { exoPlayer.playWhenReady = false }
    }

    fun stop() {
        handler.post {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            notifyStopped()
        }
    }

    fun seekTo(positionMs: Long) {
        handler.post { exoPlayer.seekTo(positionMs) }
    }

    fun setVolume(volume: Float) {
        handler.post { exoPlayer.volume = volume.coerceIn(0f, 1f) }
    }

    val isPlaying: Boolean get() = exoPlayer.isPlaying
    val currentPositionMs: Long get() = exoPlayer.currentPosition
    val durationMs: Long get() = exoPlayer.duration
    val currentPositionSec: Double get() = exoPlayer.currentPosition / 1000.0
    val durationSec: Double get() = if (exoPlayer.duration > 0) exoPlayer.duration / 1000.0 else 0.0

    fun release() {
        stopPositionPolling()
        exoPlayer.release()
    }

    private fun startPositionPolling() {
        stopPositionPolling()
        val runnable = object : Runnable {
            override fun run() {
                if (exoPlayer.isPlaying) {
                    callbacks.forEach {
                        it.onPositionUpdate(exoPlayer.currentPosition, exoPlayer.duration)
                    }
                    handler.postDelayed(this, 1000)
                }
            }
        }
        positionPoller = runnable
        handler.postDelayed(runnable, 1000)
    }

    private fun stopPositionPolling() {
        positionPoller?.let { handler.removeCallbacks(it) }
        positionPoller = null
    }

    private fun notifyPlaying() {
        callbacks.forEach { it.onPlaying() }
    }

    private fun notifyPaused() {
        callbacks.forEach { it.onPaused() }
    }

    private fun notifyStopped() {
        callbacks.forEach { it.onStopped() }
    }
}
