package com.airplaypc.android.server

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * Serves live HLS playlist + segments from [rootDir].
 * Marks [onPlaylistFetched] when an external client reads the playlist.
 */
class HlsHttpServer(
    port: Int,
    private val rootDir: File,
    private val onLog: (String) -> Unit = {},
    private val onPlaylistFetched: (() -> Unit)? = null,
) : NanoHTTPD(port) {

    @Volatile
    var expectClientPrefix: String? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimStart('/')
        val remote = session.remoteIpAddress ?: "?"
        onLog("HTTP ${session.method} /$uri von $remote")

        if (uri.endsWith(".m3u8")) {
            onPlaylistFetched?.invoke()
        }

        val file = if (uri.isEmpty()) {
            File(rootDir, "live.m3u8")
        } else {
            File(rootDir, uri).canonicalFile
        }
        if (!file.path.startsWith(rootDir.canonicalPath) || !file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }

        val mime = when {
            file.name.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            file.name.endsWith(".ts") -> "video/mp2t"
            file.name.endsWith(".m4s") -> "video/iso.segment"
            file.name.endsWith(".mp4") -> "video/mp4"
            else -> "application/octet-stream"
        }
        val stream = FileInputStream(file)
        val resp = newFixedLengthResponse(Response.Status.OK, mime, stream, file.length())
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }
}
