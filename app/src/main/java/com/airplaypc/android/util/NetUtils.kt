package com.airplaypc.android.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** Prefer non-loopback IPv4 on Wi‑Fi / Ethernet (same idea as PC LAN pick). */
    fun localIpv4(): String {
        val candidates = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("169.254.")) continue
                    candidates += host
                }
            }
        }
        return candidates.firstOrNull { it.startsWith("192.168.") }
            ?: candidates.firstOrNull { it.startsWith("10.") }
            ?: candidates.firstOrNull()
            ?: "127.0.0.1"
    }
}
