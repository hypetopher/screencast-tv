package com.screencast.tv.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.screencast.tv.R
import com.screencast.tv.common.CastEvent
import com.screencast.tv.common.CastEventBus
import com.screencast.tv.common.NetworkUtils
import java.util.UUID

class DlnaService : Service() {

    companion object {
        private const val TAG = "DlnaService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "dlna_service"
        const val DLNA_PORT = 49152
        const val ACTION_CAST_EVENT = "com.screencast.tv.CAST_EVENT"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSITION = "position"
    }

    private var dlnaServer: DlnaServer? = null
    private var ssdpHandler: SsdpHandler? = null
    private var avTransport: AVTransportService? = null
    private val deviceUuid = UUID.randomUUID().toString()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        startDlnaServer()
    }

    override fun onDestroy() {
        stopDlnaServer()
        super.onDestroy()
    }

    private fun startDlnaServer() {
        val localIp = NetworkUtils.getLocalIpAddress() ?: "0.0.0.0"
        Log.d(TAG, "Starting DLNA on $localIp:$DLNA_PORT")

        avTransport = AVTransportService(
            onCastEvent = { event -> broadcastCastEvent(event) },
            getPositionSec = { getSharedPositionSec() },
            getDurationSec = { getSharedDurationSec() },
            getIsPlaying = { getSharedIsPlaying() }
        )

        dlnaServer = DlnaServer(DLNA_PORT, deviceUuid, "ScreenCast TV", avTransport!!).also {
            try {
                it.start()
                Log.d(TAG, "DLNA HTTP server started on port $DLNA_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start DLNA server", e)
            }
        }

        ssdpHandler = SsdpHandler(deviceUuid, DLNA_PORT, localIp).also {
            it.start()
            it.sendAlive()
        }
    }

    private fun stopDlnaServer() {
        ssdpHandler?.stop()
        dlnaServer?.stop()
        ssdpHandler = null
        dlnaServer = null
    }

    private fun broadcastCastEvent(event: CastEvent) {
        Log.d(TAG, "Broadcasting cast event: $event")
        CastEventBus.post(event)
    }

    // These are updated from the activity via companion object
    private fun getSharedPositionSec(): Double = PlaybackState.positionSec
    private fun getSharedDurationSec(): Double = PlaybackState.durationSec
    private fun getSharedIsPlaying(): Boolean = PlaybackState.isPlaying

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "DLNA Service", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("DLNA receiver active")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    /** Shared playback state accessible from services */
    object PlaybackState {
        @Volatile var positionSec: Double = 0.0
        @Volatile var durationSec: Double = 0.0
        @Volatile var isPlaying: Boolean = false
    }
}
