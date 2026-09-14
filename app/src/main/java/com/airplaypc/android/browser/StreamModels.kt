package com.airplaypc.android.browser

enum class MediaKind { VIDEO, AUDIO, UNKNOWN }

data class DetectedStream(
    val url: String,
    val title: String,
    val pageHost: String,
    val mimeHint: String = "",
    val kind: MediaKind = MediaKind.UNKNOWN,
    val score: Int = 0,
) {
    val isHls: Boolean
        get() = url.contains(".m3u8", ignoreCase = true) ||
            mimeHint.contains("mpegurl", ignoreCase = true)

    val isAudio: Boolean
        get() = kind == MediaKind.AUDIO ||
            mimeHint.contains("audio", ignoreCase = true) ||
            AUDIO_EXT.any { url.contains(it, ignoreCase = true) }

    val isMp4: Boolean
        get() = url.contains(".mp4", ignoreCase = true) ||
            mimeHint.contains("mp4", ignoreCase = true)

    companion object {
        val AUDIO_EXT = listOf(
            ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac", ".oga", ".weba",
        )
    }
}

data class StreamQuality(
    val label: String,
    val url: String,
    val height: Int = 0,
    val bandwidth: Int = 0,
    /** Optional HLS master body with AUDIO + one video variant — served locally for ATV. */
    val localPlaylistBody: String? = null,
)
