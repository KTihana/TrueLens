package com.truelens.camera.modes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.truelens.camera.grading.BitmapGrader
import com.truelens.camera.grading.LightroomGrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StopMotionState(
    val frameCount: Int = 0,
    val lastFrameBitmap: Bitmap? = null,
    val onionSkinOpacity: Float = 0.42f,
    val onionSkinEnabled: Boolean = true,
    val fps: Int = 12,
    val isExporting: Boolean = false
) {
    val durationSec: Float
        get() = if (fps <= 0) 0f else frameCount.toFloat() / fps.toFloat()

    companion object {
        val FPS_OPTIONS = listOf(6, 12, 15, 24)
    }
}

/**
 * Compiles captured Stop-Motion frames into a clean H.264 MP4 video saved to Movies/TrueLens
 * using Android's hardware MediaCodec Surface encoder + MediaMuxer.
 */
object StopMotionExporter {

    private const val TAG = "StopMotionExporter"

    suspend fun exportToMp4(
        context: Context,
        frames: List<Bitmap>,
        fps: Int,
        grade: LightroomGrade
    ): Uri? = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext null

        // Round dimensions to multiples of 16 for universal H.264 hardware encoder compatibility
        val first = frames.first()
        val maxLong = 1280
        val scale = minOf(1f, maxLong.toFloat() / maxOf(first.width, first.height))
        val width = (((first.width * scale).toInt() / 16) * 16).coerceAtLeast(320)
        val height = (((first.height * scale).toInt() / 16) * 16).coerceAtLeast(320)

        val tempFile = File(context.cacheDir, "stopmo_tmp_${System.currentTimeMillis()}.mp4")
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val encCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encCodec
            encCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encCodec.createInputSurface()
            encCodec.start()

            val outMuxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = outMuxer
            var trackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val dstRect = Rect(0, 0, width, height)

            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    encCodec.signalEndOfInputStream()
                }
                while (true) {
                    val outIdx = encCodec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = outMuxer.addTrack(encCodec.outputFormat)
                            outMuxer.start()
                            muxerStarted = true
                        }
                        outIdx >= 0 -> {
                            val encoded = encCodec.getOutputBuffer(outIdx)
                            if (encoded != null && bufferInfo.size > 0 && muxerStarted &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                outMuxer.writeSampleData(trackIndex, encoded, bufferInfo)
                            }
                            encCodec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break
                            }
                        }
                    }
                }
            }

            for (rawFrame in frames) {
                val frame = if (!grade.isNeutral) BitmapGrader.applyGrade(rawFrame, grade) else rawFrame
                val canvas: Canvas = inputSurface.lockHardwareCanvas()
                try {
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(frame, null, dstRect, paint)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
                if (frame !== rawFrame) frame.recycle()
                drainEncoder(endOfStream = false)
                // Small pace to ensure distinct presentation timestamps
                Thread.sleep((1000L / fps.coerceAtLeast(1)).coerceIn(20L, 120L))
            }

            drainEncoder(endOfStream = true)
            inputSurface.release()

            codec.stop()
            codec.release()
            codec = null
            if (muxerStarted) muxer.stop()
            muxer.release()
            muxer = null

            // Copy finished MP4 into MediaStore Movies/TrueLens
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "TrueLens_StopMo_$stamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TrueLens")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    tempFile.inputStream().use { it.copyTo(out) }
                }
            }
            tempFile.delete()
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Stop motion export failed", e)
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
            tempFile.delete()
            null
        }
    }
}
