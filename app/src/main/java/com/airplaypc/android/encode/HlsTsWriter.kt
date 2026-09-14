package com.airplaypc.android.encode

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal MPEG-TS muxer for Annex-B H.264 (+ optional ADTS AAC) → .ts segments + live.m3u8.
 */
class HlsTsWriter(
    private val outputDir: File,
    private val targetDurationSec: Double = 2.0,
    private val listSize: Int = 6,
) {
    private val videoPid = 0x100
    private val audioPid = 0x101
    private val pmtPid = 0x1000
    private var continuityVideo = 0
    private var continuityAudio = 0
    private var continuityPat = 0
    private var continuityPmt = 0

    private var segmentIndex = 0
    private val playlistSegments = ArrayDeque<Pair<String, Double>>()
    private var segmentStartPtsUs = -1L
    private var currentSeg: FileOutputStream? = null
    private var currentSegName: String? = null
    private var currentSegBytes = 0
    private var hasAudio = false

    private val spsPpsLock = Any()
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var wroteIdrInSegment = false

    fun setSpsPps(spsNal: ByteArray, ppsNal: ByteArray) {
        synchronized(spsPpsLock) {
            sps = spsNal.copyOf()
            pps = ppsNal.copyOf()
        }
    }

    fun enableAudio(enabled: Boolean) {
        hasAudio = enabled
    }

    @Synchronized
    fun writeH264AnnexB(data: ByteArray, ptsUs: Long, isKey: Boolean) {
        ensureSegment(ptsUs, isKey)
        val out = currentSeg ?: return
        // PAT + PMT at start of every keyframe / segment
        if (isKey || currentSegBytes == 0) {
            writePatPmt(out)
        }
        val payload = ByteArrayOutputStream()
        if (isKey) {
            synchronized(spsPpsLock) {
                sps?.let { payload.write(startCode()); payload.write(it) }
                pps?.let { payload.write(startCode()); payload.write(it) }
            }
            wroteIdrInSegment = true
        }
        payload.write(data)
        writePes(out, videoPid, payload.toByteArray(), ptsUs, isVideo = true)
    }

    @Synchronized
    fun writeAacAdts(adtsFrame: ByteArray, ptsUs: Long) {
        if (!hasAudio) return
        ensureSegment(ptsUs, forceNew = false)
        val out = currentSeg ?: return
        writePes(out, audioPid, adtsFrame, ptsUs, isVideo = false)
    }

    @Synchronized
    fun close() {
        closeSegment(finalize = true)
    }

    private fun ensureSegment(ptsUs: Long, forceNew: Boolean) {
        if (segmentStartPtsUs < 0) {
            openSegment(ptsUs)
            return
        }
        val elapsed = (ptsUs - segmentStartPtsUs) / 1_000_000.0
        if (forceNew && wroteIdrInSegment && elapsed >= targetDurationSec * 0.85) {
            closeSegment(finalize = false)
            openSegment(ptsUs)
        } else if (elapsed >= targetDurationSec * 1.5 && currentSeg != null) {
            closeSegment(finalize = false)
            openSegment(ptsUs)
        }
    }

    private fun openSegment(ptsUs: Long) {
        outputDir.mkdirs()
        val name = "seg_%05d.ts".format(segmentIndex++)
        currentSegName = name
        currentSeg = FileOutputStream(File(outputDir, name))
        currentSegBytes = 0
        segmentStartPtsUs = ptsUs
        wroteIdrInSegment = false
        writePatPmt(currentSeg!!)
    }

    private fun closeSegment(finalize: Boolean) {
        val name = currentSegName
        val start = segmentStartPtsUs
        val out = currentSeg
        currentSeg = null
        currentSegName = null
        if (out != null && name != null && start >= 0) {
            out.flush()
            out.close()
            val dur = ((System.nanoTime() / 1000) - start).coerceAtLeast(1_000_000) / 1_000_000.0
            // Prefer PTS-based duration when possible
            val duration = targetDurationSec.coerceIn(1.0, 4.0)
            playlistSegments.addLast(name to duration)
            while (playlistSegments.size > listSize) {
                val old = playlistSegments.removeFirst()
                File(outputDir, old.first).delete()
            }
            writePlaylist(endList = finalize)
        }
        if (finalize) {
            writePlaylist(endList = true)
        }
    }

    private fun writePlaylist(endList: Boolean) {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:${kotlin.math.ceil(targetDurationSec).toInt()}\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:${(segmentIndex - playlistSegments.size).coerceAtLeast(0)}\n")
        for ((name, dur) in playlistSegments) {
            sb.append("#EXTINF:${"%.3f".format(dur)},\n")
            sb.append(name).append('\n')
        }
        if (endList) sb.append("#EXT-X-ENDLIST\n")
        File(outputDir, "live.m3u8").writeText(sb.toString())
    }

    private fun writePatPmt(out: FileOutputStream) {
        out.write(buildTsPacket(0x0000, continuityPat++, buildPat()))
        out.write(buildTsPacket(pmtPid, continuityPmt++, buildPmt()))
        currentSegBytes += 188 * 2
    }

    private fun buildPat(): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(0x00) // pointer
        b.write(0x00) // table_id
        // section length later
        val body = ByteArrayOutputStream()
        body.write(0x00); body.write(0x01) // transport_stream_id
        body.write(0xC1) // version/current
        body.write(0x00); body.write(0x00) // section/last
        body.write(0x00); body.write(0x01) // program_number
        body.write(((0xE0) or (pmtPid shr 8)) and 0xFF)
        body.write(pmtPid and 0xFF)
        val bodyBytes = body.toByteArray()
        val sectionLen = bodyBytes.size + 4 // + CRC
        b.write(0xB0 or ((sectionLen shr 8) and 0x0F))
        b.write(sectionLen and 0xFF)
        b.write(bodyBytes)
        b.write(crc32(b.toByteArray().copyOfRange(1, b.size())))
        return b.toByteArray()
    }

    private fun buildPmt(): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(0x00)
        b.write(0x02)
        val body = ByteArrayOutputStream()
        body.write(0x00); body.write(0x01) // program_number
        body.write(0xC1)
        body.write(0x00); body.write(0x00)
        body.write(0xE0 or (videoPid shr 8))
        body.write(videoPid and 0xFF)
        body.write(0xF0); body.write(0x00) // program_info_length
        // video stream
        body.write(0x1B) // H.264
        body.write(0xE0 or (videoPid shr 8))
        body.write(videoPid and 0xFF)
        body.write(0xF0); body.write(0x00)
        if (hasAudio) {
            body.write(0x0F) // AAC ADTS
            body.write(0xE0 or (audioPid shr 8))
            body.write(audioPid and 0xFF)
            body.write(0xF0); body.write(0x00)
        }
        val bodyBytes = body.toByteArray()
        val sectionLen = bodyBytes.size + 4
        b.write(0xB0 or ((sectionLen shr 8) and 0x0F))
        b.write(sectionLen and 0xFF)
        b.write(bodyBytes)
        b.write(crc32(b.toByteArray().copyOfRange(1, b.size())))
        return b.toByteArray()
    }

    private fun writePes(out: FileOutputStream, pid: Int, payload: ByteArray, ptsUs: Long, isVideo: Boolean) {
        val pts = (ptsUs * 90) / 1000 // 90 kHz
        val pesHeader = ByteArrayOutputStream()
        pesHeader.write(0x00); pesHeader.write(0x00); pesHeader.write(0x01)
        pesHeader.write(if (isVideo) 0xE0 else 0xC0)
        // length 0 for video allowed
        val ptsBytes = ptsBits(pts)
        val headerData = ByteArrayOutputStream()
        headerData.write(0x80) // marker
        headerData.write(0x80) // PTS only
        headerData.write(0x05)
        headerData.write(ptsBytes)
        val hd = headerData.toByteArray()
        val packetLen = hd.size + payload.size
        if (!isVideo) {
            pesHeader.write((packetLen shr 8) and 0xFF)
            pesHeader.write(packetLen and 0xFF)
        } else {
            pesHeader.write(0x00); pesHeader.write(0x00)
        }
        pesHeader.write(hd)
        pesHeader.write(payload)
        val pes = pesHeader.toByteArray()

        var offset = 0
        var first = true
        val cc = if (isVideo) continuityVideo else continuityAudio
        var cont = cc
        while (offset < pes.size) {
            val packet = ByteArray(188)
            packet[0] = 0x47
            val pidHi = (pid shr 8) and 0x1F
            packet[1] = ((if (first) 0x40 else 0x00) or pidHi).toByte()
            packet[2] = (pid and 0xFF).toByte()
            val adaptAndCc = 0x10 or (cont and 0x0F)
            cont = (cont + 1) and 0x0F
            packet[3] = adaptAndCc.toByte()
            val space = 184
            val remain = pes.size - offset
            if (remain >= space) {
                System.arraycopy(pes, offset, packet, 4, space)
                offset += space
            } else {
                // stuffing via adaptation field
                val stuff = space - remain
                packet[3] = (0x30 or (packet[3].toInt() and 0x0F)).toByte()
                packet[4] = (stuff - 1).toByte()
                if (stuff > 1) {
                    packet[5] = 0x00
                    for (i in 6 until 4 + stuff) packet[i] = 0xFF.toByte()
                }
                System.arraycopy(pes, offset, packet, 4 + stuff, remain)
                offset += remain
            }
            out.write(packet)
            currentSegBytes += 188
            first = false
        }
        if (isVideo) continuityVideo = cont else continuityAudio = cont
    }

    private fun buildTsPacket(pid: Int, continuity: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(188)
        packet[0] = 0x47
        packet[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        packet[2] = (pid and 0xFF).toByte()
        val stuff = 184 - payload.size
        if (stuff > 0) {
            packet[3] = (0x30 or (continuity and 0x0F)).toByte()
            packet[4] = (stuff - 1).toByte()
            if (stuff > 1) {
                packet[5] = 0x00
                for (i in 6 until 4 + stuff) packet[i] = 0xFF.toByte()
            }
            System.arraycopy(payload, 0, packet, 4 + stuff, payload.size)
        } else {
            packet[3] = (0x10 or (continuity and 0x0F)).toByte()
            System.arraycopy(payload, 0, packet, 4, 184)
        }
        return packet
    }

    private fun ptsBits(pts: Long): ByteArray {
        val p = ByteArray(5)
        p[0] = (0x20 or (((pts shr 30) and 0x07).toInt() shl 1) or 1).toByte()
        p[1] = ((pts shr 22) and 0xFF).toByte()
        p[2] = ((((pts shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        p[3] = ((pts shr 7) and 0xFF).toByte()
        p[4] = ((((pts) and 0x7F).toInt() shl 1) or 1).toByte()
        return p
    }

    private fun startCode() = byteArrayOf(0, 0, 0, 1)

    private fun crc32(data: ByteArray): ByteArray {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000.toInt() != 0) {
                    (crc shl 1) xor 0x04C11DB7
                } else {
                    crc shl 1
                }
            }
        }
        return byteArrayOf(
            ((crc shr 24) and 0xFF).toByte(),
            ((crc shr 16) and 0xFF).toByte(),
            ((crc shr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte(),
        )
    }
}

/** Split MediaCodec AVCC length-prefixed buffer into Annex-B for TS. */
fun avccToAnnexB(data: ByteBuffer, size: Int): Pair<ByteArray, Boolean> {
    val bytes = ByteArray(size)
    data.get(bytes)
    // Already Annex-B?
    if (bytes.size >= 4 &&
        bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
        (bytes[2] == 1.toByte() || (bytes[2] == 0.toByte() && bytes[3] == 1.toByte()))
    ) {
        var isKey = false
        var i = 0
        while (i + 4 < bytes.size) {
            val sc = if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()
            ) {
                4
            } else if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte()) {
                3
            } else {
                i++
                continue
            }
            val nalType = bytes[i + sc].toInt() and 0x1F
            if (nalType == 5) isKey = true
            i += sc + 1
        }
        return bytes to isKey
    }
    val out = ByteArrayOutputStream()
    var i = 0
    var isKey = false
    while (i + 4 <= bytes.size) {
        val len = ((bytes[i].toInt() and 0xFF) shl 24) or
            ((bytes[i + 1].toInt() and 0xFF) shl 16) or
            ((bytes[i + 2].toInt() and 0xFF) shl 8) or
            (bytes[i + 3].toInt() and 0xFF)
        i += 4
        if (len <= 0 || i + len > bytes.size) break
        val nalType = bytes[i].toInt() and 0x1F
        if (nalType == 5) isKey = true
        out.write(byteArrayOf(0, 0, 0, 1))
        out.write(bytes, i, len)
        i += len
    }
    return out.toByteArray() to isKey
}

fun extractSpsPpsFromCsd(csd0: ByteBuffer, csd1: ByteBuffer?): Pair<ByteArray, ByteArray> {
    fun strip(buf: ByteBuffer): ByteArray {
        val a = ByteArray(buf.remaining())
        buf.mark()
        buf.get(a)
        buf.reset()
        // may start with 0x00000001
        return if (a.size > 4 && a[0] == 0.toByte() && a[1] == 0.toByte() && a[2] == 0.toByte() && a[3] == 1.toByte()) {
            a.copyOfRange(4, a.size)
        } else if (a.size > 3 && a[0] == 0.toByte() && a[1] == 0.toByte() && a[2] == 1.toByte()) {
            a.copyOfRange(3, a.size)
        } else a
    }
    val sps = strip(csd0)
    val pps = if (csd1 != null) strip(csd1) else byteArrayOf()
    return sps to pps
}

object SegmentCounter {
    val value = AtomicInteger(0)
}
