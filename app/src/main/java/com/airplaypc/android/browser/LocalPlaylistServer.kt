package com.airplaypc.android.browser

import com.airplaypc.android.util.NetUtils
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.atomic.AtomicReference

/**
 * Serves short-lived HLS master playlists so Apple TV can fetch AUDIO+video together.
 */
class LocalPlaylistServer(port: Int = 8766) : NanoHTTPD(port) {
    private val body = AtomicReference<String?>(null)
    private val name = AtomicReference("cast.m3u8")

    companion object {
        /** Process-wide server so cast survives Activity teardown / screen lock. */
        val shared: LocalPlaylistServer by lazy { LocalPlaylistServer() }
    }

    fun publish(playlistBody: String, fileName: String = "cast.m3u8"): String {
        name.set(fileName)
        body.set(playlistBody)
        if (!wasStarted()) {
            start(SOCKET_READ_TIMEOUT, false)
        }
        val ip = NetUtils.localIpv4()
        return "http://$ip:$listeningPort/$fileName"
    }

    fun stopServer() {
        body.set(null)
        try {
            stop()
        } catch (_: Throwable) {
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val playlist = body.get()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "gone")
        val resp = newFixedLengthResponse(
            Response.Status.OK,
            "application/vnd.apple.mpegurl",
            playlist,
        )
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }
}
