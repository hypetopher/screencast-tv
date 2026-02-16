package com.screencast.tv.dlna

import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

class SsdpHandler(
    private val deviceUuid: String,
    private val serverPort: Int,
    private val localIp: String
) {
    companion object {
        private const val TAG = "SsdpHandler"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    }

    private var socket: MulticastSocket? = null
    private var running = false
    private var listenerThread: Thread? = null

    fun start() {
        running = true
        listenerThread = Thread {
            try {
                val group = InetAddress.getByName(SSDP_ADDRESS)
                val sock = MulticastSocket(SSDP_PORT)
                sock.reuseAddress = true
                // Try to join multicast group
                try {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    for (intf in interfaces) {
                        if (intf.isUp && !intf.isLoopback && intf.supportsMulticast()) {
                            try {
                                sock.joinGroup(java.net.InetSocketAddress(SSDP_ADDRESS, SSDP_PORT), intf)
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (_: Exception) {
                    @Suppress("DEPRECATION")
                    sock.joinGroup(group)
                }
                socket = sock

                val buf = ByteArray(4096)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        if (message.contains("M-SEARCH", ignoreCase = true)) {
                            handleMSearch(message, packet.address, packet.port)
                        }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "SSDP receive error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SSDP socket error", e)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        listenerThread?.interrupt()
    }

    fun sendAlive() {
        Thread {
            try {
                val group = InetAddress.getByName(SSDP_ADDRESS)
                val message = buildNotifyAlive()
                val data = message.toByteArray()
                val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
                val sock = MulticastSocket()
                sock.send(packet)
                sock.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SSDP alive", e)
            }
        }.start()
    }

    private fun handleMSearch(message: String, address: InetAddress, port: Int) {
        // Respond if searching for our device type or ssdp:all
        val st = message.lines().find { it.startsWith("ST:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: return

        val shouldRespond = st == "ssdp:all" ||
                st == "upnp:rootdevice" ||
                st == DEVICE_TYPE ||
                st == "urn:schemas-upnp-org:service:AVTransport:1"

        if (!shouldRespond) return

        val response = buildMSearchResponse(st)
        try {
            val data = response.toByteArray()
            val packet = DatagramPacket(data, data.size, address, port)
            val sock = MulticastSocket()
            sock.send(packet)
            sock.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SSDP response", e)
        }
    }

    private fun buildMSearchResponse(st: String): String {
        return "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "EXT:\r\n" +
                "LOCATION: http://$localIp:$serverPort/description.xml\r\n" +
                "SERVER: Android/1.0 UPnP/1.0 ScreenCastTV/1.0\r\n" +
                "ST: $st\r\n" +
                "USN: uuid:$deviceUuid::$st\r\n" +
                "\r\n"
    }

    private fun buildNotifyAlive(): String {
        return "NOTIFY * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: http://$localIp:$serverPort/description.xml\r\n" +
                "NT: upnp:rootdevice\r\n" +
                "NTS: ssdp:alive\r\n" +
                "SERVER: Android/1.0 UPnP/1.0 ScreenCastTV/1.0\r\n" +
                "USN: uuid:$deviceUuid::upnp:rootdevice\r\n" +
                "\r\n"
    }
}
