package com.screencast.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.media.PlaybackTransportControlGlue
import com.screencast.tv.common.AppPrefs
import com.screencast.tv.common.CastEvent
import com.screencast.tv.common.CastEventBus
import com.screencast.tv.dlna.DlnaService
import com.screencast.tv.player.PlayerCallback
import com.screencast.tv.player.VideoPlayerManager
import com.screencast.tv.MirrorActivity

class PlaybackFragment : VideoSupportFragment() {

    companion object {
        private const val TAG = "PlaybackFragment"
    }

    private var playerManager: VideoPlayerManager? = null
    private var transportControlGlue: PlaybackTransportControlGlue<LeanbackPlayerAdapter>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var subtitleView: SubtitleView? = null

    private val castListener: (CastEvent) -> Unit = { event ->
        Log.d(TAG, "Playback cast event: $event")
        when (event) {
            is CastEvent.Play -> {
                playerManager?.playUrl(event.url, (event.startPosition * 1000).toLong(), event.subtitleUrl)
            }
            is CastEvent.StartMirroring -> {
                activity?.startActivity(android.content.Intent(activity, MirrorActivity::class.java))
                activity?.finish()
            }
            is CastEvent.Pause -> playerManager?.pause()
            is CastEvent.Resume -> playerManager?.play()
            is CastEvent.Stop -> {
                playerManager?.stop()
                activity?.finish()
            }
            is CastEvent.Seek -> {
                playerManager?.seekTo((event.positionSeconds * 1000).toLong())
            }
            is CastEvent.SetVolume -> {
                playerManager?.setVolume(event.volume)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val context = requireContext()
        playerManager = VideoPlayerManager(context)

        // Setup Leanback transport controls
        val playerAdapter = LeanbackPlayerAdapter(context, playerManager!!.exoPlayer, 1000)
        val glueHost = VideoSupportFragmentGlueHost(this)
        transportControlGlue = PlaybackTransportControlGlue(context, playerAdapter).also {
            it.host = glueHost
            it.title = arguments?.getString("video_title") ?: "Video"
            it.isSeekEnabled = true
        }

        // Auto-hide controls overlay after playback starts
        isControlsOverlayAutoHideEnabled = true

        // Wire up subtitle rendering
        playerManager?.exoPlayer?.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                Log.d(TAG, "onCues: ${cueGroup.cues.size} cues, subtitleView=${subtitleView != null}")
                subtitleView?.setCues(cueGroup.cues)
            }
        })

        // Track playback state for DLNA/AirPlay status queries
        playerManager?.addCallback(object : PlayerCallback {
            override fun onPlaying() {
                DlnaService.PlaybackState.isPlaying = true
                // Hide controls overlay shortly after playback begins
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    if (isResumed) hideControlsOverlay(true)
                }, 3000)
            }
            override fun onPaused() {
                DlnaService.PlaybackState.isPlaying = false
            }
            override fun onStopped() {
                DlnaService.PlaybackState.isPlaying = false
                DlnaService.PlaybackState.positionSec = 0.0
                DlnaService.PlaybackState.durationSec = 0.0
            }
            override fun onPositionUpdate(positionMs: Long, durationMs: Long) {
                DlnaService.PlaybackState.positionSec = positionMs / 1000.0
                DlnaService.PlaybackState.durationSec = if (durationMs > 0) durationMs / 1000.0 else 0.0
            }
            override fun onError(message: String) {
                Log.e(TAG, "Player error: $message")
                activity?.finish()
            }
        })

        // Register for cast control events
        CastEventBus.register(castListener)

        // Start playback
        val videoUrl = arguments?.getString("video_url")
        val subtitleUrl = arguments?.getString("subtitle_url")
        val startPosition = arguments?.getDouble("start_position", 0.0) ?: 0.0
        if (videoUrl != null && (videoUrl.startsWith("http://") || videoUrl.startsWith("https://"))) {
            Log.d(TAG, "Starting playback: $videoUrl, subtitle: $subtitleUrl")
            playerManager?.playUrl(videoUrl, (startPosition * 1000).toLong(), subtitleUrl)
        } else {
            Log.e(TAG, "Invalid video URL: $videoUrl")
            activity?.finish()
        }
    }

    override fun onStart() {
        super.onStart()
        subtitleView = activity?.findViewById(R.id.subtitle_view)
        subtitleView?.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, AppPrefs.subtitleSizeSp)
        Log.d(TAG, "SubtitleView found: ${subtitleView != null}")
    }

    override fun onPause() {
        super.onPause()
        playerManager?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        CastEventBus.unregister(castListener)
        DlnaService.PlaybackState.isPlaying = false
        playerManager?.release()
        playerManager = null
        super.onDestroy()
    }
}
