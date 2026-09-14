package com.airplaypc.android.browser

import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Collects castable media URLs from network sniffing + JS video/audio probe.
 * Prefers HLS masters (with audio) over video-only media playlists.
 */
class VideoDetector(
    private val onStream: (DetectedStream) -> Unit,
) {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val best = AtomicReference<DetectedStream?>(null)

    @Volatile
    var pageUrl: String = ""

    @Volatile
    var pageTitle: String = ""

    fun reset() {
        seen.clear()
        best.set(null)
    }

    fun considerUrl(raw: String?, mime: String? = null, forcedKind: MediaKind? = null) {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty() || url.startsWith("blob:") || url.startsWith("data:")) return
        val key = stripQueryNoise(url)
        val isNew = seen.add(key)
        val kind = forcedKind ?: classify(url, mime)
        val host = try {
            java.net.URI(pageUrl).host ?: pageHostOf(url)
        } catch (_: Throwable) {
            pageHostOf(url)
        }
        val stream = DetectedStream(
            url = url,
            title = pageTitle.ifBlank { host },
            pageHost = host,
            mimeHint = mime.orEmpty(),
            kind = kind,
            score = score(url, mime, kind),
        )
        val prev = best.get()
        if (prev != null && stream.score < prev.score) return
        if (prev != null && stream.score == prev.score && !isNew) return
        best.set(stream)
        onStream(stream)
    }

    fun onRequest(request: WebResourceRequest?) {
        if (request == null) return
        val url = request.url?.toString() ?: return
        val accept = request.requestHeaders["Accept"]
        val range = request.requestHeaders["Range"]
        // Media segment fetches often have Range — still useful for discovering playlist roots
        considerUrl(url, accept)
        if (range != null && url.contains(".m3u8", ignoreCase = true)) {
            considerUrl(url, "application/vnd.apple.mpegurl")
        }
    }

    fun injectProbe(webView: WebView) {
        webView.evaluateJavascript(PROBE_JS, null)
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onVideoUrl(url: String?, title: String?) {
            if (!title.isNullOrBlank()) pageTitle = title
            considerUrl(url, forcedKind = MediaKind.VIDEO)
        }

        @JavascriptInterface
        fun onAudioUrl(url: String?, title: String?) {
            if (!title.isNullOrBlank()) pageTitle = title
            considerUrl(url, "audio/*", forcedKind = MediaKind.AUDIO)
        }
    }

    private fun pageHostOf(url: String): String =
        try {
            java.net.URI(url).host ?: "Media"
        } catch (_: Throwable) {
            "Media"
        }

    companion object {
        private val VIDEO_EXT = listOf(
            ".m3u8", ".mpd", ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".flv",
            ".ts", ".m2ts", ".mts", ".3gp", ".3g2", ".ogv", ".f4v", ".wmv", ".asf",
            ".mpeg", ".mpg", ".vob",
        )
        private val AUDIO_EXT = DetectedStream.AUDIO_EXT

        private fun stripQueryNoise(url: String): String =
            url.substringBefore('#').lowercase()

        private fun classify(url: String, mime: String?): MediaKind {
            val u = url.lowercase()
            val m = mime?.lowercase().orEmpty()
            if (m.startsWith("audio") || AUDIO_EXT.any { u.contains(it) }) return MediaKind.AUDIO
            if (m.startsWith("video") || m.contains("mpegurl") || VIDEO_EXT.any { u.contains(it) }) {
                return MediaKind.VIDEO
            }
            return MediaKind.UNKNOWN
        }

        private fun isLikelyMedia(url: String, mime: String?): Boolean {
            val u = url.lowercase()
            if (VIDEO_EXT.any { u.contains(it) } || AUDIO_EXT.any { u.contains(it) }) return true
            // query-style: format=m3u8 / type=mp4
            if (u.contains("m3u8") || u.contains("format=mp4") || u.contains("mime=video") ||
                u.contains("mime=audio") || u.contains("/manifest") || u.contains("playlist")
            ) {
                return true
            }
            val m = mime?.lowercase().orEmpty()
            return m.contains("mpegurl") || m.contains("dash") || m.startsWith("video") ||
                m.startsWith("audio") || m.contains("mp4") || m.contains("matroska")
        }

        private fun score(url: String, mime: String?, kind: MediaKind): Int {
            val u = url.lowercase()
            var s = 10
            if (u.contains(".m3u8")) s += 40
            if (u.contains("master")) s += 50
            if (u.contains("index.m3u8") || u.contains("playlist.m3u8")) s += 35
            if (u.contains("manifest")) s += 25
            if (Regex("""/(?:\d+p|video_\d+|rendition)/""").containsMatchIn(u)) s -= 15
            if (u.contains("fragment") || u.contains("seg-") || u.contains("segment")) s -= 40
            if (u.contains(".m4s") || u.endsWith(".ts") || u.contains(".ts?")) s -= 50
            if (kind == MediaKind.AUDIO) s += 20
            if (u.contains(".mp4") || u.contains(".m4a") || u.contains(".mp3")) s += 15
            if (mime?.contains("mpegurl") == true) s += 20
            // Prefer https
            if (u.startsWith("https://")) s += 5
            return s
        }

        private val PROBE_JS = """
            (function() {
              function emitVideo(u) {
                if (!u || u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
                try { AirPlayDetect.onVideoUrl(String(u), document.title || ''); } catch (e) {}
              }
              function emitAudio(u) {
                if (!u || u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
                try { AirPlayDetect.onAudioUrl(String(u), document.title || ''); } catch (e) {}
              }
              function scan() {
                var videos = document.querySelectorAll('video');
                for (var i = 0; i < videos.length; i++) {
                  var v = videos[i];
                  emitVideo(v.currentSrc || v.src);
                  var sources = v.querySelectorAll('source');
                  for (var j = 0; j < sources.length; j++) emitVideo(sources[j].src);
                }
                var audios = document.querySelectorAll('audio');
                for (var i = 0; i < audios.length; i++) {
                  var a = audios[i];
                  emitAudio(a.currentSrc || a.src);
                  var sources = a.querySelectorAll('source');
                  for (var j = 0; j < sources.length; j++) emitAudio(sources[j].src);
                }
              }
              scan();
              if (!window.__airplayObs) {
                window.__airplayObs = new MutationObserver(scan);
                window.__airplayObs.observe(document.documentElement, {
                  childList:true, subtree:true, attributes:true, attributeFilter:['src']
                });
                document.addEventListener('play', function(e) {
                  var t = e.target;
                  if (!t) return;
                  if (t.tagName === 'VIDEO') emitVideo(t.currentSrc || t.src);
                  if (t.tagName === 'AUDIO') emitAudio(t.currentSrc || t.src);
                }, true);
              }
              setInterval(scan, 2000);
            })();
        """.trimIndent()
    }
}
