package com.airplaypc.android.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.airplaypc.android.encode.HlsTsWriter
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Captures device playback audio (Android 10+) and encodes AAC ADTS into the HLS writer.
 */
class AudioCapturer(
    private val projection: MediaProjection,
    private val writer: HlsTsWriter,
    private val onLog: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onLog("AudioCapture braucht Android 10+")
            return
        }
        if (!running.compareAndSet(false, true)) return
        writer.enableAudio(true)
        worker = thread(name = "AudioCapturer", isDaemon = true) {
            runCapture()
        }
    }

    fun stop() {
        running.set(false)
        worker?.join(1500)
        worker = null
    }

    private fun runCapture() {
        val sampleRate = 44100
        val channelCount = 2
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            onLog("AudioRecord buffer ungültig")
            return
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        var record: AudioRecord? = null
        var encoder: MediaCodec? = null
        try {
            record = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onLog("AudioRecord Init fehlgeschlagen (Mikrofon-Recht?)")
                return
            }

            val aacFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }

            record.startRecording()
            onLog("Audio-Capture gestartet (AAC 128k)")

            val pcm = ByteArray(minBuf)
            val info = MediaCodec.BufferInfo()
            var ptsUs = 0L
            val frameSamples = 1024
            val bytesPerFrame = frameSamples * channelCount * 2

            while (running.get()) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue

                var offset = 0
                while (offset < n && running.get()) {
                    val inIndex = encoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = encoder.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        val chunk = minOf(bytesPerFrame, n - offset, inBuf.capacity())
                        inBuf.put(pcm, offset, chunk)
                        encoder.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0)
                        val samples = chunk / (channelCount * 2)
                        ptsUs += samples * 1_000_000L / sampleRate
                        offset += chunk
                    } else break
                }

                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (outIndex >= 0) {
                        val outBuf = encoder.getOutputBuffer(outIndex)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val aac = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(aac)
                            val adts = addAdtsHeader(aac, sampleRate, channelCount)
                            writer.writeAacAdts(adts, info.presentationTimeUs)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "audio", t)
            onLog("Audio-Fehler: ${t.message}")
        } finally {
            try {
                record?.stop()
            } catch (_: Throwable) {
            }
            record?.release()
            try {
                encoder?.stop()
                encoder?.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun addAdtsHeader(aac: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val freqIdx = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            else -> 4
        }
        val chanCfg = channels
        val packetLen = aac.size + 7
        val out = ByteArray(packetLen)
        out[0] = 0xFF.toByte()
        out[1] = 0xF9.toByte()
        out[2] = (((2 and 0x3) shl 6) or ((freqIdx and 0xF) shl 2) or ((chanCfg shr 2) and 0x1)).toByte()
        out[3] = (((chanCfg and 0x3) shl 6) or ((packetLen shr 11) and 0x3)).toByte()
        out[4] = ((packetLen shr 3) and 0xFF).toByte()
        out[5] = (((packetLen and 0x7) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
        System.arraycopy(aac, 0, out, 7, aac.size)
        return out
    }

    companion object {
        private const val TAG = "AudioCapturer"
    }
}
