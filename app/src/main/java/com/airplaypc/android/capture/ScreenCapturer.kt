package com.airplaypc.android.capture

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.airplaypc.android.encode.HlsTsWriter
import com.airplaypc.android.encode.QualityPreset
import com.airplaypc.android.encode.avccToAnnexB
import com.airplaypc.android.encode.extractSpsPpsFromCsd

class ScreenCapturer(
    private val context: Context,
    private val quality: QualityPreset,
    private val writer: HlsTsWriter,
    private val onLog: (String) -> Unit,
) {
    var projection: MediaProjection? = null
        private set

    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var startPtsUs = -1L
    private var stopProjectionOnStop = true

    fun start(resultCode: Int, data: Intent) {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection null")
        startWithProjection(proj, stopProjectionOnStop = true)
    }

    fun startWithProjection(proj: MediaProjection, stopProjectionOnStop: Boolean = false) {
        this.stopProjectionOnStop = stopProjectionOnStop
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                onLog("MediaProjection gestoppt")
            }
        }, null)

        val width = quality.width
        val height = quality.height
        val fps = quality.fps
        val bitrate = quality.videoBitrate

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        thread = HandlerThread("ScreenEncoder").also { it.start() }
        handler = Handler(thread!!.looper)

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { enc ->
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                ) {
                    try {
                        val buf = codec.getOutputBuffer(index) ?: return
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val pts = if (info.presentationTimeUs > 0) info.presentationTimeUs else {
                                if (startPtsUs < 0) startPtsUs = System.nanoTime() / 1000
                                System.nanoTime() / 1000 - startPtsUs
                            }
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val slice = buf.slice()
                            val (annexB, keyFromNal) = avccToAnnexB(slice, info.size)
                            val isKey = keyFromNal ||
                                (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            if (annexB.isNotEmpty()) {
                                writer.writeH264AnnexB(annexB, pts, isKey)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (t: Throwable) {
                        Log.e(TAG, "encode out", t)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    onLog("Encoder-Fehler: ${e.message}")
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    try {
                        val csd0 = format.getByteBuffer("csd-0")
                        val csd1 = format.getByteBuffer("csd-1")
                        if (csd0 != null) {
                            val (sps, pps) = extractSpsPpsFromCsd(csd0, csd1)
                            writer.setSpsPps(sps, pps)
                            onLog("H.264 SPS/PPS ok (${width}x${height}@${fps})")
                        }
                    } catch (t: Throwable) {
                        onLog("CSD parse: ${t.message}")
                    }
                }
            }, handler)
            enc.start()
        }

        val density = context.resources.displayMetrics.densityDpi
        virtualDisplay = proj.createVirtualDisplay(
            "Beam",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            handler,
        )
        onLog("Screen-Capture gestartet")
    }

    fun stop() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        inputSurface?.release()
        inputSurface = null
        if (stopProjectionOnStop) {
            try {
                projection?.stop()
            } catch (_: Throwable) {
            }
        }
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    companion object {
        private const val TAG = "ScreenCapturer"
    }
}
