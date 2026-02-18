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
import com.screencast.tv.common.AppPrefs
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
        AppPrefs.init(this)
        startForegroundNotification()
        startDlnaServer()
    }

    override fun onDestroy() {
        stopDlnaServer()
        super.onDestroy()
    }

    private fun startDlnaServer() {
        val localIp = NetworkUtils.getLocalIpAddress() ?: "0.0.0.0"
        Log.i(TAG, "Starting DLNA on $localIp:$DLNA_PORT")

        avTransport = AVTransportService(
            onCastEvent = { event -> broadcastCastEvent(event) },
            getPositionSec = { getSharedPositionSec() },
            getDurationSec = { getSharedDurationSec() },
            getIsPlaying = { getSharedIsPlaying() }
        )

        Thread {
            try {
                val server = tryStartServer(DLNA_PORT) ?: tryStartServer(0)

                dlnaServer = server
                val actualPort = server?.listeningPort ?: DLNA_PORT

                ssdpHandler = SsdpHandler(deviceUuid, actualPort, localIp).also {
                    it.start()
                    it.sendAlive()
                }
                Log.i(TAG, "DLNA + SSDP ready on port $actualPort")
            } catch (e: Exception) {
                Log.e(TAG, "DLNA startup thread crashed", e)
            }
        }.start()
    }

    private fun stopDlnaServer() {
        ssdpHandler?.stop()
        dlnaServer?.stop()
        ssdpHandler = null
        dlnaServer = null
    }

    private fun tryStartServer(port: Int): DlnaServer? {
        Log.i(TAG, "Attempting DLNA server on port $port")
        var server: DlnaServer? = null
        try {
            server = DlnaServer(port, deviceUuid, AppPrefs.deviceName, avTransport!!)
            server.start()
            Thread.sleep(1000)
            if (server.isAlive) {
                Log.i(TAG, "DLNA HTTP server started on port ${server.listeningPort}")
                return server
            } else {
                Log.w(TAG, "DLNA server on port $port not alive after start")
                server.stop()
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DLNA server on port $port: ${e.javaClass.simpleName}: ${e.message}")
            try { server?.stop() } catch (_: Exception) {}
            return null
        }
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
