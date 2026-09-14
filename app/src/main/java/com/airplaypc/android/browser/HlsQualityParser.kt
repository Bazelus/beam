package com.airplaypc.android.browser

import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object HlsQualityParser {
    private val resolution = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)")
    private val bandwidth = Pattern.compile("BANDWIDTH=(\\d+)")

    /**
     * Parse HLS into quality options. Variant URLs are wrapped as mini-masters that keep
     * EXT-X-MEDIA AUDIO lines so Apple TV still plays sound.
     */
    fun parse(masterUrl: String, pageTitle: String = "Original"): List<StreamQuality> {
        val body = fetchText(masterUrl) ?: return listOf(StreamQuality("Original", masterUrl))
        if (!body.contains("#EXT-X-STREAM-INF")) {
            // Media playlist or audio-only — cast as-is
            return listOf(StreamQuality("Original", masterUrl))
        }

        val lines = body.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val mediaLines = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA:")) {
                mediaLines += absolutizeMediaLine(masterUrl, line)
            }
        }

        val out = mutableListOf<StreamQuality>()
        // Always offer full master first (best A/V sync on Apple TV)
        out += StreamQuality("Original (Auto + Ton)", masterUrl)

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val h = resolution.matcher(line).let { m -> if (m.find()) m.group(2)?.toIntOrNull() ?: 0 else 0 }
                val bw = bandwidth.matcher(line).let { m -> if (m.find()) m.group(1)?.toIntOrNull() ?: 0 else 0 }
                val next = lines.getOrNull(i + 1)?.trim()
                if (next != null && !next.startsWith("#")) {
                    val absVideo = resolveUrl(masterUrl, next)
                    val label = when {
                        h >= 2160 -> "4K · ${h}p"
                        h >= 1440 -> "1440p"
                        h >= 1080 -> "1080p"
                        h >= 720 -> "720p"
                        h >= 480 -> "480p"
                        h > 0 -> "${h}p"
                        bw > 0 -> "≈ ${bw / 1000} kbps"
                        else -> "Variante"
                    }
                    val mini = buildMiniMaster(mediaLines, line, absVideo)
                    // If no separate audio MEDIA, playing the variant URL is fine (muxed)
                    val playUrl = absVideo
                    val localBody = if (mediaLines.any { it.contains("TYPE=AUDIO", ignoreCase = true) }) {
                        mini
                    } else {
                        null
                    }
                    out += StreamQuality(
                        label = if (localBody != null) "$label + Ton" else label,
                        url = playUrl,
                        height = h,
                        bandwidth = bw,
                        localPlaylistBody = localBody,
                    )
                    i += 2
                    continue
                }
            }
            i++
        }

        val rest = out.drop(1).sortedByDescending { it.height.takeIf { h -> h > 0 } ?: it.bandwidth }
        return listOf(out.first()) + rest.distinctBy { it.url + (it.localPlaylistBody?.hashCode() ?: 0) }
    }

    private fun buildMiniMaster(
        mediaLines: List<String>,
        streamInf: String,
        absoluteVariantUrl: String,
    ): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        for (m in mediaLines) {
            sb.append(m).append('\n')
        }
        sb.append(streamInf).append('\n')
        sb.append(absoluteVariantUrl).append('\n')
        return sb.toString()
    }

    private fun absolutizeMediaLine(base: String, line: String): String {
        val uriMatch = Regex("""URI="([^"]+)"""").find(line) ?: return line
        val raw = uriMatch.groupValues[1]
        val abs = resolveUrl(base, raw)
        return line.replace("""URI="$raw"""", """URI="$abs"""")
    }

    private fun resolveUrl(base: String, ref: String): String {
        return try {
            URL(URL(base), ref).toString()
        } catch (_: Throwable) {
            ref
        }
    }

    fun fetchText(url: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15",
                )
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        }
    }
}
