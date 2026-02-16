package com.screencast.tv

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.screencast.tv.airplay.mirror.AirPlayMirrorRenderer
import com.screencast.tv.common.CastEvent
import com.screencast.tv.common.CastEventBus

class MirrorActivity : FragmentActivity() {
    companion object {
        private const val TAG = "MirrorActivity"
    }

    private val castListener: (CastEvent) -> Unit = { event ->
        if (event is CastEvent.Stop) finish()
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.d(TAG, "surfaceCreated")
            AirPlayMirrorRenderer.attachSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.d(TAG, "surfaceChanged ${width}x$height")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "surfaceDestroyed")
            AirPlayMirrorRenderer.detachSurface(holder.surface)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mirror)

        Log.d(TAG, "Mirror activity started")

        val surfaceView = findViewById<SurfaceView>(R.id.mirror_surface)
        surfaceView.holder.addCallback(surfaceCallback)
        CastEventBus.register(castListener)

        // Listen for video dimension changes
        AirPlayMirrorRenderer.dimensionListener = { videoWidth, videoHeight ->
            runOnUiThread { resizeSurfaceView(videoWidth, videoHeight) }
        }
    }

    override fun onDestroy() {
        AirPlayMirrorRenderer.dimensionListener = null
        AirPlayMirrorRenderer.reset()
        CastEventBus.unregister(castListener)
        super.onDestroy()
    }

    /**
     * Resize the SurfaceView to maintain the video's aspect ratio within the screen.
     * Portrait content will be pillarboxed (black bars on sides).
     * Landscape content will be letterboxed (black bars top/bottom) if needed.
     */
    private fun resizeSurfaceView(videoWidth: Int, videoHeight: Int) {
        val surfaceView = findViewById<SurfaceView>(R.id.mirror_surface) ?: return
        val container = surfaceView.parent as? FrameLayout ?: return

        // Use post to ensure container dimensions are available
        container.post {
            val parentWidth = container.width
            val parentHeight = container.height
            if (parentWidth <= 0 || parentHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return@post

            val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
            val parentAspect = parentWidth.toFloat() / parentHeight.toFloat()

            val newWidth: Int
            val newHeight: Int
            if (videoAspect > parentAspect) {
                // Video is wider than screen (landscape video) - fit to width
                newWidth = parentWidth
                newHeight = (parentWidth / videoAspect).toInt()
            } else {
                // Video is taller than screen (portrait video) - fit to height
                newHeight = parentHeight
                newWidth = (parentHeight * videoAspect).toInt()
            }

            Log.d(TAG, "Resize SurfaceView: video=${videoWidth}x$videoHeight -> view=${newWidth}x$newHeight (parent=${parentWidth}x$parentHeight)")

            val params = surfaceView.layoutParams as FrameLayout.LayoutParams
            params.width = newWidth
            params.height = newHeight
            params.gravity = Gravity.CENTER
            surfaceView.layoutParams = params
        }
    }
}
