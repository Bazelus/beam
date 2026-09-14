package com.airplaypc.android.discovery

import android.content.Context
import android.net.wifi.WifiManager

/** Android drops mDNS unless a multicast lock is held. */
class MulticastLockHolder(context: Context) {
    private val lock: WifiManager.MulticastLock? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("beam-mdns").also {
            it.setReferenceCounted(true)
        }
    } catch (_: Throwable) {
        null
    }

    fun acquire() {
        try {
            lock?.acquire()
        } catch (_: Throwable) {
        }
    }

    fun release() {
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (_: Throwable) {
        }
    }
}
