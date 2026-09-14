package com.airplaypc.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.airplaypc.android.R
import com.airplaypc.android.airplay.PyAtvBridge
import com.airplaypc.android.capture.AudioCapturer
import com.airplaypc.android.capture.ScreenCapturer
import com.airplaypc.android.encode.HlsTsWriter
import com.airplaypc.android.encode.QualityPreset
import com.airplaypc.android.server.HlsHttpServer
import com.airplaypc.android.ui.MainActivity
import com.airplaypc.android.util.NetUtils
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class StreamService : Service() {

    private val running = AtomicBoolean(false)
    private var writer: HlsTsWriter? = null
    private var screen: ScreenCapturer? = null
    private var audio: AudioCapturer? = null
    private var server: HlsHttpServer? = null
    private var fetchFlag = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStream()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (running.get()) return START_STICKY
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                val qualityLabel = intent.getStringExtra(EXTRA_QUALITY) ?: QualityPreset.P1080_30.label
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: ""
                if (data == null) {
                    broadcastLog("Keine Capture-Permission")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNotification()
                thread(name = "StreamStart") {
                    try {
                        startStream(resultCode, data, QualityPreset.fromLabel(qualityLabel), deviceId, deviceName)
                    } catch (t: Throwable) {
                        broadcastLog("Start-Fehler: ${t.message}")
                        broadcastStatus("Fehler")
                        stopStream()
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "stream"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, getString(R.string.channel_stream), NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_streaming))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    private fun startStream(
        resultCode: Int,
        data: Intent,
        quality: QualityPreset,
        deviceId: String,
        deviceName: String,
    ) {
        if (!running.compareAndSet(false, true)) return
        fetchFlag.set(false)
        val dir = File(cacheDir, "hls").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        val w = HlsTsWriter(dir)
        writer = w
        broadcastLog("Qualität: ${quality.label}")

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection null")

        val capturer = ScreenCapturer(this, quality, w, ::broadcastLog)
        screen = capturer
        capturer.startWithProjection(projection, stopProjectionOnStop = true)

        val aud = AudioCapturer(projection, w, ::broadcastLog)
        audio = aud
        aud.start()

        val ip = NetUtils.localIpv4()
        val port = HLS_PORT
        val http = HlsHttpServer(port, dir, onLog = ::broadcastLog) {
            fetchFlag.set(true)
        }
        server = http
        http.start()
        val url = "http://$ip:$port/live.m3u8"
        broadcastLog("HLS: $url")
        broadcastStatus("Stream läuft — warte auf Apple TV…")

        // Wait for a couple segments
        var waited = 0
        while (waited < 15000 && running.get()) {
            val pl = File(dir, "live.m3u8")
            if (pl.exists() && pl.readText().contains(".ts")) break
            Thread.sleep(200)
            waited += 200
        }

        if (deviceId.isNotEmpty()) {
            broadcastLog("play_url → $deviceName")
            try {
                PyAtvBridge.playUrl(deviceId, url) {
                    // optional: could check fetchFlag
                }
                broadcastStatus("Screenshare aktiv")
                broadcastLog("Apple TV Playback gestartet")
            } catch (t: Throwable) {
                broadcastLog("play_url Fehler: ${t.message}")
                broadcastStatus("Stream lokal ok, TV-Fehler")
            }
        } else {
            broadcastStatus("Stream lokal (kein Gerät)")
        }
    }

    private fun stopStream() {
        if (!running.getAndSet(false)) {
            // still cleanup
        }
        try {
            PyAtvBridge.stopPlayback()
        } catch (_: Throwable) {
        }
        try {
            audio?.stop()
        } catch (_: Throwable) {
        }
        audio = null
        try {
            screen?.stop()
        } catch (_: Throwable) {
        }
        screen = null
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
        try {
            server?.stop()
        } catch (_: Throwable) {
        }
        server = null
        broadcastStatus("Gestoppt")
        broadcastLog("Stream beendet")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopStream()
        super.onDestroy()
    }

    private fun broadcastLog(msg: String) {
        sendBroadcast(Intent(ACTION_LOG).setPackage(packageName).putExtra(EXTRA_MSG, msg))
    }

    private fun broadcastStatus(msg: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MSG, msg))
    }

    companion object {
        const val HLS_PORT = 8765
        const val ACTION_START = "com.airplaypc.android.START"
        const val ACTION_STOP = "com.airplaypc.android.STOP"
        const val ACTION_LOG = "com.airplaypc.android.LOG"
        const val ACTION_STATUS = "com.airplaypc.android.STATUS"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_DEVICE_NAME = "deviceName"
        const val EXTRA_MSG = "msg"

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            quality: QualityPreset,
            deviceId: String,
            deviceName: String,
        ) {
            val i = Intent(context, StreamService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_QUALITY, quality.label)
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StreamService::class.java).setAction(ACTION_STOP))
        }
    }
}
