package com.screencast.tv.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.screencast.tv.R
import com.screencast.tv.common.AppPrefs
import com.screencast.tv.common.CastEvent
import com.screencast.tv.common.CastEventBus
import com.screencast.tv.common.NetworkUtils
import com.screencast.tv.dlna.DlnaService
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

class AirPlayService : Service() {

    companion object {
        private const val TAG = "AirPlayService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "airplay_service"
        const val AIRPLAY_PORT = 7000
    }

    private var airPlayServer: AirPlayServer? = null
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        startForegroundNotification()
        acquireMulticastLock()
        startAirPlayServer()
        // Register mDNS after server starts so we can use its Ed25519 public key
        registerMdnsService()
    }

    override fun onDestroy() {
        unregisterMdnsService()
        stopAirPlayServer()
        releaseMulticastLock()
        super.onDestroy()
    }

    private fun startAirPlayServer() {
        airPlayServer = AirPlayServer(
            rtspPort = AIRPLAY_PORT,
            onCastEvent = { event -> broadcastCastEvent(event) },
            getPositionSec = { DlnaService.PlaybackState.positionSec },
            getDurationSec = { DlnaService.PlaybackState.durationSec },
            getIsPlaying = { DlnaService.PlaybackState.isPlaying }
        ).also {
            try {
                it.start()
                Log.d(TAG, "AirPlay server started on port $AIRPLAY_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AirPlay server", e)
            }
        }
    }

    private fun stopAirPlayServer() {
        airPlayServer?.stop()
        airPlayServer = null
    }

    private fun registerMdnsService() {
        Thread {
            try {
                val localIp = NetworkUtils.getLocalIpAddress()
                if (localIp == null) {
                    Log.e(TAG, "No local IP address found, cannot register mDNS")
                    return@Thread
                }
                Log.d(TAG, "Registering mDNS on IP: $localIp")
                val addr = java.net.InetAddress.getByName(localIp)
                jmdns = JmDNS.create(addr, AppPrefs.deviceName.replace(" ", ""))

                val mac = NetworkUtils.getMacAddress()
                val deviceId = NetworkUtils.macToString(mac)
                Log.d(TAG, "Device ID: $deviceId")

                // AirPlay 2 compatible feature flags
                // 0x5A7FFFF7 = supports video, photo, screen mirroring, audio
                val features = "0x5A7FFFF7,0x1E"

                // Use actual Ed25519 public key from pairing
                val pk = airPlayServer?.pairing?.getPublicKeyHex()
                    ?: "99996b6e4e14dd9e097e23b1b944e0e203744518c8750f0baf3b3a40ce13e94f"
                Log.d(TAG, "mDNS pk = $pk")

                val airplayProps = mapOf(
                    "deviceid" to deviceId,
                    "features" to features,
                    "model" to "AppleTV3,2",
                    "srcvers" to "220.68",
                    "vv" to "2",
                    "pk" to pk,
                    "pi" to "2e388006-13ba-4041-9a67-25dd4a43d536",
                    "flags" to "0x44"
                )

                val airplayService = ServiceInfo.create(
                    "_airplay._tcp.local.",
                    AppPrefs.deviceName,
                    AIRPLAY_PORT,
                    0, 0,
                    airplayProps
                )
                jmdns?.registerService(airplayService)
                Log.d(TAG, "mDNS _airplay._tcp registered on port $AIRPLAY_PORT")

                // Also register RAOP service (required for AirPlay discovery on iOS)
                val raopName = "${deviceId.replace(":", "")}@${AppPrefs.deviceName}"
                val raopProps = mapOf(
                    "am" to "AppleTV3,2",
                    "cn" to "0,1,2,3",
                    "da" to "true",
                    "et" to "0,3,5",
                    "ft" to features,
                    "md" to "0,1,2",
                    "pw" to "false",
                    "sf" to "0x44",
                    "sr" to "44100",
                    "ss" to "16",
                    "sv" to "false",
                    "tp" to "UDP",
                    "txtvers" to "1",
                    "vn" to "65537",
                    "vs" to "220.68",
                    "vv" to "2"
                )

                val raopService = ServiceInfo.create(
                    "_raop._tcp.local.",
                    raopName,
                    AIRPLAY_PORT,
                    0, 0,
                    raopProps
                )
                jmdns?.registerService(raopService)
                Log.d(TAG, "mDNS _raop._tcp registered as '$raopName' on port $AIRPLAY_PORT")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to register mDNS", e)
            }
        }.start()
    }

    private fun unregisterMdnsService() {
        Thread {
            try {
                jmdns?.unregisterAllServices()
                jmdns?.close()
                jmdns = null
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering mDNS", e)
            }
        }.start()
    }

    private fun acquireMulticastLock() {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifiManager.createMulticastLock("screencast_airplay").also {
            it.setReferenceCounted(true)
            it.acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        multicastLock = null
    }

    private fun broadcastCastEvent(event: CastEvent) {
        Log.d(TAG, "Broadcasting cast event: $event")
        CastEventBus.post(event)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AirPlay Service", NotificationManager.IMPORTANCE_LOW)
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
            .setContentText("AirPlay receiver active")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
