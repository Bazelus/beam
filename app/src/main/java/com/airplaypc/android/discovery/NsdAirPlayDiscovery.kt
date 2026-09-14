package com.airplaypc.android.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

data class NsdHost(
    val name: String,
    val host: String,
    val port: Int,
    val type: String,
)

/**
 * Native Android mDNS discovery for AirPlay / RAOP / companion-link.
 */
class NsdAirPlayDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun discover(timeoutMs: Long = 6_000L): List<NsdHost> {
        val found = ConcurrentHashMap<String, NsdHost>()
        val types = listOf("_airplay._tcp.", "_raop._tcp.", "_companion-link._tcp.")
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        fun hostOf(info: NsdServiceInfo): String? {
            if (Build.VERSION.SDK_INT >= 34) {
                val addrs = info.hostAddresses
                val v4 = addrs.firstOrNull { it.hostAddress?.contains('.') == true }
                return (v4 ?: addrs.firstOrNull())?.hostAddress
            }
            @Suppress("DEPRECATION")
            return info.host?.hostAddress
        }

        fun addResolved(info: NsdServiceInfo, type: String) {
            val host = hostOf(info) ?: return
            val rawName = info.serviceName ?: return
            val nice = rawName.substringAfter("@").ifBlank { rawName }
            found["$host|$rawName"] = NsdHost(nice, host, info.port, type)
        }

        for (type in types) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.w(TAG, "start fail $serviceType $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo == null) return
                    try {
                        nsd.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                                override fun onServiceResolved(resolved: NsdServiceInfo?) {
                                    if (resolved != null) addResolved(resolved, type)
                                }
                            },
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "resolve", t)
                    }
                }
            }
            listeners += listener
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (t: Throwable) {
                Log.w(TAG, "discover $type", t)
            }
        }

        try {
            Thread.sleep(timeoutMs.coerceIn(3000L, 12000L))
        } catch (_: InterruptedException) {
        }
        for (listener in listeners) {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Throwable) {
            }
        }
        return found.values.toList()
    }

    companion object {
        private const val TAG = "NsdAirPlay"
    }
}
