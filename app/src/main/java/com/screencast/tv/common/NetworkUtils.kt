package com.screencast.tv.common

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                // Prefer wlan/eth interfaces
                val name = intf.name.lowercase()
                if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("en")) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            // Fallback: any non-loopback IPv4
            val allInterfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in allInterfaces) {
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun getMacAddress(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return generateFakeMac()
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en")) {
                    val mac = intf.hardwareAddress
                    if (mac != null && mac.size == 6) return mac
                }
            }
        } catch (_: Exception) {
        }
        return generateFakeMac()
    }

    private fun generateFakeMac(): ByteArray {
        // Generate a stable fake MAC for mDNS advertisement
        return byteArrayOf(0x08, 0x00, 0x27, 0x01, 0x02, 0x03)
    }

    fun macToString(mac: ByteArray): String {
        return mac.joinToString(":") { "%02X".format(it) }
    }
}
