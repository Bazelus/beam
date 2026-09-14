package com.airplaypc.android.airplay

import android.content.Context
import com.airplaypc.android.discovery.MulticastLockHolder
import com.airplaypc.android.discovery.NsdAirPlayDiscovery
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject

data class AtvDevice(
    val name: String,
    val address: String,
    val identifier: String,
    val model: String,
    val paired: Boolean,
) {
    val label: String get() = "$name ($address)"
}

object PyAtvBridge {
    @Volatile
    private var initialized = false

    private fun module() = Python.getInstance().getModule("airplay_bridge")

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val dir = context.filesDir.resolve("beam")
            dir.mkdirs()
            module().callAttr("init_storage", dir.absolutePath)
            initialized = true
        }
    }

    /**
     * Discover Apple TVs using MulticastLock + Android NSD hints + pyatv scan.
     */
    fun scan(context: Context, timeoutSec: Double = 8.0, extraHosts: List<String> = emptyList()): List<AtvDevice> {
        val multicast = MulticastLockHolder(context)
        multicast.acquire()
        try {
            val nsdHosts = try {
                NsdAirPlayDiscovery(context).discover(((timeoutSec * 1000).toLong()).coerceIn(4000L, 10000L))
            } catch (_: Throwable) {
                emptyList()
            }
            val hints = JSONArray()
            for (h in nsdHosts) {
                hints.put(
                    JSONObject()
                        .put("host", h.host)
                        .put("name", h.name)
                        .put("port", h.port)
                        .put("type", h.type),
                )
            }
            for (ip in extraHosts) {
                if (ip.isNotBlank()) {
                    hints.put(JSONObject().put("host", ip.trim()))
                }
            }
            val json = module().callAttr("scan", timeoutSec, hints.toString()).toString()
            return parseDevices(json)
        } finally {
            multicast.release()
        }
    }

    private fun parseDevices(json: String): List<AtvDevice> {
        val arr = JSONArray(json)
        val out = ArrayList<AtvDevice>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += AtvDevice(
                name = o.optString("name"),
                address = o.optString("address"),
                identifier = o.optString("identifier"),
                model = o.optString("model"),
                paired = o.optBoolean("paired"),
            )
        }
        return out
    }

    fun hasCredentials(identifier: String): Boolean =
        module().callAttr("has_credentials", identifier).toBoolean()

    fun pairStart(identifier: String): String =
        module().callAttr("pair_start", identifier).toString()

    fun pairPin(pin: String) {
        module().callAttr("pair_pin", pin)
    }

    fun pairFinish(identifier: String, name: String): String =
        module().callAttr("pair_finish", identifier, name).toString()

    fun playUrl(identifier: String, url: String, mediaType: String = "video", onProgress: (() -> Unit)? = null) {
        onProgress?.invoke()
        module().callAttr("play_url", identifier, url, mediaType)
    }

    fun stopPlayback() {
        module().callAttr("stop_playback")
    }
}
