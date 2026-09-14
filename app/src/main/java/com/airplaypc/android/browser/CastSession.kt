package com.airplaypc.android.browser

import android.content.Context
import com.airplaypc.android.R
import com.airplaypc.android.airplay.AtvDevice
import com.airplaypc.android.airplay.PyAtvBridge
import com.airplaypc.android.service.CastKeepAliveService

/**
 * Holds selected Apple TV + active cast URL.
 */
class CastSession(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("cast", Context.MODE_PRIVATE)

    private var lastDeviceId: String? = prefs.getString("deviceId", null)
    private var lastDeviceName: String? = prefs.getString("deviceName", null)
    private var lastDeviceAddress: String? = prefs.getString("deviceAddress", null)

    var device: AtvDevice? = null
        private set

    val isCasting: Boolean
        get() = Companion.isCasting

    val activeUrl: String?
        get() = Companion.activeUrl

    init {
        PyAtvBridge.init(appContext)
    }

    fun selectDevice(device: AtvDevice) {
        this.device = device
        lastDeviceId = device.identifier
        lastDeviceName = device.name
        lastDeviceAddress = device.address
        prefs.edit()
            .putString("deviceId", device.identifier)
            .putString("deviceName", device.name)
            .putString("deviceAddress", device.address)
            .apply()
    }

    fun connectedLabel(): String {
        val d = device
        return when {
            d != null -> d.name
            !lastDeviceName.isNullOrBlank() -> lastDeviceName!!
            else -> appContext.getString(R.string.not_connected)
        }
    }

    fun isLinked(): Boolean {
        val id = ensureDeviceId() ?: return false
        return PyAtvBridge.hasCredentials(id)
    }

    fun ensureDeviceId(): String? = device?.identifier ?: lastDeviceId

    fun play(url: String, mediaType: String = "video") {
        val id = ensureDeviceId() ?: throw IllegalStateException(appContext.getString(R.string.err_no_apple_tv))
        if (!PyAtvBridge.hasCredentials(id)) {
            throw IllegalStateException(appContext.getString(R.string.err_not_paired))
        }
        // Start FGS first so Android does not freeze networking during play_url.
        CastKeepAliveService.start(appContext, connectedLabel())
        PyAtvBridge.playUrl(id, url, mediaType)
        Companion.isCasting = true
        Companion.activeUrl = url
    }

    fun stop() {
        try {
            PyAtvBridge.stopPlayback()
        } finally {
            markStopped()
            try {
                LocalPlaylistServer.shared.stopServer()
            } catch (_: Throwable) {
            }
            CastKeepAliveService.stop(appContext)
        }
    }

    companion object {
        @Volatile
        var isCasting: Boolean = false
            private set

        @Volatile
        var activeUrl: String? = null
            private set

        fun markStopped() {
            isCasting = false
            activeUrl = null
        }
    }
}
