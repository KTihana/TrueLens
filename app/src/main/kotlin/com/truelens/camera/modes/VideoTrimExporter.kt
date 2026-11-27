package com.truelens.camera.modes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fast Lossless Video Trimmer, Audio Muter & High-Res Frame Grabber.
 *
 * - Trims any MP4 cleanly between [startMs] and [endMs] using MediaExtractor + MediaMuxer
 *   without re-encoding (instant speed, 100% original bitrate & sharpness).
 * - Optionally strips the audio track when [muteAudio] is enabled.
 * - Extracts a full-resolution still photo frame at any exact timestamp via MediaMetadataRetriever.
 */
object VideoTrimExporter {

    private const val TAG = "TrueLensVideoTrim"

    suspend fun trimVideo(
        context: Context,
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        muteAudio: Boolean
    ): Uri? = withContext(Dispatchers.IO) {
        if (endMs <= startMs + 250L) return@withContext null

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = if (muteAudio) "MuteTrim" else "Trim"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "TrueLens_${suffix}_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TrueLens")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val outUri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        val pfd = cr.openFileDescriptor(outUri, "w") ?: run {
            cr.delete(outUri, null, null)
            return@withContext null
        }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var success = false
        try {
            extractor.setDataSource(context, sourceUri, null)

            // Preserve original rotation metadata
            val retriever = MediaMetadataRetriever()
            val rotationDeg = try {
                retriever.setDataSource(context, sourceUri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } catch (_: Exception) {
                0
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotationDeg in setOf(0, 90, 180, 270)) {
                muxer.setOrientationHint(rotationDeg)
            }

            val trackMap = mutableMapOf<Int, Int>()
            var maxBufferSize = 1024 * 1024
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")
                if (isVideo || (isAudio && !muteAudio)) {
                    extractor.selectTrack(i)
                    trackMap[i] = muxer.addTrack(fmt)
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val sz = fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (sz > maxBufferSize) maxBufferSize = sz
                    }
                }
            }

            if (trackMap.isEmpty()) return@withContext null

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            muxer.start()

            val buffer = ByteBuffer.allocate(maxBufferSize)
            val info = MediaCodec.BufferInfo()
            var basePtsUs = -1L

            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break

                val srcTrack = extractor.sampleTrackIndex
                val dstTrack = trackMap[srcTrack]
                if (dstTrack != null && sampleTimeUs >= startUs - 500_000L) {
                    if (basePtsUs < 0L) basePtsUs = sampleTimeUs
                    info.presentationTimeUs = (sampleTimeUs - basePtsUs).coerceAtLeast(0L)
                    @Suppress("DEPRECATION")
                    val flags = extractor.sampleFlags
                    info.flags = if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else 0
                    muxer.writeSampleData(dstTrack, buffer, info)
                }
                extractor.advance()
            }
            success = basePtsUs >= 0L
        } catch (e: Exception) {
            Log.e(TAG, "Video trim failed", e)
            success = false
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }

        if (success) {
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            cr.update(outUri, done, null, null)
            outUri
        } else {
            try { cr.delete(outUri, null, null) } catch (_: Exception) {}
            null
        }
    }

    /** Extracts a crisp high-res JPEG frame at [positionMs] from any video and saves it to Pictures/TrueLens. */
    suspend fun grabFrameToGallery(
        context: Context,
        videoUri: Uri,
        positionMs: Long
    ): Uri? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        try {
            retriever.setDataSource(context, videoUri)
            frame = retriever.getFrameAtTime(
                positionMs.coerceAtLeast(0L) * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: retriever.frameAtTime
            if (frame == null) return@withContext null

            val out = ByteArrayOutputStream()
            frame.compress(Bitmap.CompressFormat.JPEG, 96, out)

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "TrueLens_Frame_$stamp.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TrueLens")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val cr = context.contentResolver
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            cr.openOutputStream(uri)?.use { it.write(out.toByteArray()) }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            cr.update(uri, done, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Video frame grab failed", e)
            null
        } finally {
            frame?.recycle()
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Bakes authentic 90s VHS Camcorder look (warm analog color shift, scanlines, vignette,
     * and "PLAY ▶  SP" 90s OSD timestamp) directly into a recorded MP4 video while preserving audio!
     */
    suspend fun bakeVhsLookToMp4(
        context: Context,
        sourceUri: Uri
    ): Uri? = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var audioExtractor: MediaExtractor? = null
        val tempFile = java.io.File(context.cacheDir, "vhs_bake_${System.currentTimeMillis()}.mp4")
        try {
            retriever.setDataSource(context, sourceUri)
            val durMs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 3000L)
                .coerceIn(500L, 60_000L)
            val firstFrame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@withContext null
            val scale = minOf(1f, 720f / maxOf(firstFrame.width, firstFrame.height).toFloat())
            val width = (((firstFrame.width * scale).toInt() / 16) * 16).coerceAtLeast(320)
            val height = (((firstFrame.height * scale).toInt() / 16) * 16).coerceAtLeast(320)
            firstFrame.recycle()

            val fps = 15
            val frameCount = ((durMs / 1000.0) * fps).toInt().coerceIn(8, 300)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 3_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            enc.start()

            // Probe source audio track if present
            val ext = MediaExtractor()
            audioExtractor = ext
            ext.setDataSource(context, sourceUri, null)
            var srcAudioTrack = -1
            var srcAudioFormat: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val f = ext.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    srcAudioTrack = i
                    srcAudioFormat = f
                    break
                }
            }

            val mx = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            var videoTrackIdx = -1
            var audioTrackIdx = -1
            var muxerStarted = false
            val bufInfo = MediaCodec.BufferInfo()
            val dstRect = android.graphics.Rect(0, 0, width, height)
            val bmpPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            val warmOverlay = android.graphics.Paint().apply { color = android.graphics.Color.argb(26, 255, 195, 110) }
            val scanPaint = android.graphics.Paint().apply { color = android.graphics.Color.argb(38, 0, 0, 0); strokeWidth = 1.5f }
            val osdPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(235, 245, 248, 255)
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = (minOf(width, height) * 0.044f).coerceIn(14f, 34f)
                setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
            }
            val dateLabel = SimpleDateFormat("MMM dd yyyy", Locale.US).format(Date()).uppercase(Locale.US)

            fun drain(eos: Boolean) {
                if (eos) enc.signalEndOfInputStream()
                while (true) {
                    val outIdx = enc.dequeueOutputBuffer(bufInfo, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) break
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            videoTrackIdx = mx.addTrack(enc.outputFormat)
                            if (srcAudioFormat != null) {
                                audioTrackIdx = mx.addTrack(srcAudioFormat)
                            }
                            mx.start()
                            muxerStarted = true
                        }
                        outIdx >= 0 -> {
                            val encoded = enc.getOutputBuffer(outIdx)
                            if (encoded != null && bufInfo.size > 0 && muxerStarted &&
                                (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                encoded.position(bufInfo.offset)
                                encoded.limit(bufInfo.offset + bufInfo.size)
                                mx.writeSampleData(videoTrackIdx, encoded, bufInfo)
                            }
                            enc.releaseOutputBuffer(outIdx, false)
                            if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                }
            }

            for (i in 0 until frameCount) {
                val tMs = (i * 1000L) / fps
                val frame = retriever.getFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: continue
                val canvas = surface.lockHardwareCanvas()
                try {
                    canvas.drawColor(android.graphics.Color.BLACK)
                    canvas.drawBitmap(frame, null, dstRect, bmpPaint)
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), warmOverlay)
                    var y = 0f
                    while (y < height) {
                        canvas.drawLine(0f, y, width.toFloat(), y, scanPaint)
                        y += 4f
                    }
                    val pad = width * 0.05f
                    val secs = (tMs / 1000L).toInt()
                    val tc = String.format(Locale.US, "SP  00:%02d:%02d", secs / 60, secs % 60)
                    canvas.drawText("PLAY ▶", pad, pad + osdPaint.textSize, osdPaint)
                    canvas.drawText(tc, pad, height - pad - osdPaint.textSize * 1.3f, osdPaint)
                    canvas.drawText(dateLabel, pad, height - pad, osdPaint)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                frame.recycle()
                drain(false)
                Thread.sleep(35)
            }
            drain(true)
            surface.release()

            // Copy original audio track into VHS MP4 if present
            if (muxerStarted && srcAudioTrack >= 0 && audioTrackIdx >= 0) {
                ext.selectTrack(srcAudioTrack)
                val abuf = ByteBuffer.allocate(256 * 1024)
                val aInfo = MediaCodec.BufferInfo()
                while (true) {
                    aInfo.offset = 0
                    aInfo.size = ext.readSampleData(abuf, 0)
                    if (aInfo.size < 0) break
                    aInfo.presentationTimeUs = ext.sampleTime.coerceAtLeast(0L)
                    aInfo.flags = 0
                    mx.writeSampleData(audioTrackIdx, abuf, aInfo)
                    ext.advance()
                }
            }

            enc.stop(); enc.release(); codec = null
            if (muxerStarted) mx.stop()
            mx.release(); muxer = null

            withContext(Dispatchers.IO) {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "TrueLens_VHS_$stamp.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TrueLens")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val cr = context.contentResolver
                val outUri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
                cr.openOutputStream(outUri)?.use { os -> tempFile.inputStream().use { it.copyTo(os) } }
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                cr.update(outUri, done, null, null)
                outUri
            }
        } catch (e: Exception) {
            Log.w(TAG, "VHS bake failed (original video preserved)", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
            tempFile.delete()
        }
    }
}
