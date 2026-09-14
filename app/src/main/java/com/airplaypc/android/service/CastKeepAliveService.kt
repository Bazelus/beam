package com.airplaypc.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.airplaypc.android.R
import com.airplaypc.android.airplay.PyAtvBridge
import com.airplaypc.android.browser.CastSession
import com.airplaypc.android.ui.BrowserActivity
import kotlin.concurrent.thread

/**
 * Keeps CPU / Wi‑Fi / AirPlay session alive while casting with the screen off.
 * Uses a real MediaSession so Android 14+ does not kill mediaPlayback FGS.
 */
class CastKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var mediaSession: MediaSessionCompat? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var deviceLabel: String = "Apple TV"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                thread {
                    try {
                        PyAtvBridge.init(applicationContext)
                        PyAtvBridge.stopPlayback()
                    } catch (_: Throwable) {
                    }
                    CastSession.markStopped()
                    stopKeepAlive()
                }
                return START_NOT_STICKY
            }
            else -> {
                deviceLabel = intent?.getStringExtra(EXTRA_DEVICE) ?: getString(R.string.app_name)
                ensureMediaSession()
                requestAudioFocus()
                startForegroundCast(deviceLabel)
                acquireLocks()
            }
        }
        return START_STICKY
    }

    private fun ensureMediaSession() {
        if (mediaSession != null) return
        mediaSession = MediaSessionCompat(this, "beam_cast").also { session ->
            session.isActive = true
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getString(R.string.notif_casting))
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, deviceLabel)
                    .build(),
            )
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_PAUSE,
                    )
                    .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build(),
            )
            session.setCallback(object : MediaSessionCompat.Callback() {
                override fun onStop() {
                    startService(Intent(this@CastKeepAliveService, CastKeepAliveService::class.java).setAction(ACTION_STOP))
                }
            })
        }
    }

    private fun requestAudioFocus() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val req = audioFocusRequest
        if (req != null && Build.VERSION.SDK_INT >= 26) {
            am.abandonAudioFocusRequest(req)
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    private fun startForegroundCast(deviceName: String) {
        val channelId = "cast"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            getString(R.string.channel_cast),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = getString(R.string.notif_casting)
        }
        nm.createNotificationChannel(channel)

        val openPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, BrowserActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, CastKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val session = mediaSession
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_casting) + " · " + deviceName)
            .setSmallIcon(R.drawable.ic_airplay)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notif_stop_cast), stopPi)

        if (session != null) {
            builder.setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
        }

        val notif: Notification = builder.build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "beam:cast",
            ).also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "beam:cast-wifi").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }
        if (multicastLock?.isHeld != true) {
            multicastLock = wifi.createMulticastLock("beam:cast-mcast").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {
        }
        wifiLock = null
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Throwable) {
        }
        multicastLock = null
    }

    private fun stopKeepAlive() {
        abandonAudioFocus()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Throwable) {
        }
        mediaSession = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        abandonAudioFocus()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Throwable) {
        }
        mediaSession = null
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.airplaypc.android.CAST_STOP"
        const val ACTION_START = "com.airplaypc.android.CAST_START"
        private const val EXTRA_DEVICE = "device"
        private const val NOTIF_ID = 42

        fun start(context: Context, deviceName: String) {
            val i = Intent(context, CastKeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE, deviceName)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CastKeepAliveService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
