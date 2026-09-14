package com.airplaypc.android.encode

enum class QualityPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrate: Int,
) {
    P720_30("720p · 30 fps", 1280, 720, 30, 4_000_000),
    P1080_30("1080p · 30 fps", 1920, 1080, 30, 10_000_000),
    P1080_60("1080p · 60 fps", 1920, 1080, 60, 14_000_000);

    companion object {
        fun fromLabel(label: String): QualityPreset =
            entries.firstOrNull { it.label == label } ?: P1080_30
    }
}
