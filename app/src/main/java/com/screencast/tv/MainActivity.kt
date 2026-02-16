package com.screencast.tv

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.screencast.tv.airplay.AirPlayService
import com.screencast.tv.common.CastEvent
import com.screencast.tv.common.CastEventBus
import com.screencast.tv.common.NetworkUtils
import com.screencast.tv.dlna.DlnaService


class MainActivity : FragmentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val castListener: (CastEvent) -> Unit = { event ->
        Log.d(TAG, "Cast event received: $event")
        when (event) {
            is CastEvent.Play -> launchPlayback(event.url, event.title, event.startPosition)
            is CastEvent.StartMirroring -> launchMirroring()
            else -> {} // Other events handled by PlaybackFragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        displayIpAddress()
        startCastingServices()
        CastEventBus.register(castListener)
    }

    override fun onResume() {
        super.onResume()
        displayIpAddress()
    }

    override fun onDestroy() {
        CastEventBus.unregister(castListener)
        stopCastingServices()
        super.onDestroy()
    }

    private fun displayIpAddress() {
        val ip = NetworkUtils.getLocalIpAddress() ?: "No network"
        findViewById<TextView>(R.id.tv_ip_address)?.text = "IP: $ip"
    }

    private fun startCastingServices() {
        val airplayIntent = Intent(this, AirPlayService::class.java)
        val dlnaIntent = Intent(this, DlnaService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(airplayIntent)
            startForegroundService(dlnaIntent)
        } else {
            startService(airplayIntent)
            startService(dlnaIntent)
        }
    }

    private fun stopCastingServices() {
        stopService(Intent(this, AirPlayService::class.java))
        stopService(Intent(this, DlnaService::class.java))
    }

    private fun launchPlayback(url: String, title: String?, startPosition: Double) {
        Log.d(TAG, "Launching playback: $url")
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra("video_url", url)
            putExtra("video_title", title)
            putExtra("start_position", startPosition)
        }
        startActivity(intent)
    }

    private fun launchMirroring() {
        Log.d(TAG, "Launching mirror activity")
        val intent = Intent(this, MirrorActivity::class.java)
        startActivity(intent)
    }
}
