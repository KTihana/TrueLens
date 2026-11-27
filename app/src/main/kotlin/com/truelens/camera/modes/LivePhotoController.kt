package com.truelens.camera.modes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Motion / Live Photos Engine (1.5s pre-shutter + 1.5s post-shutter).
 *
 * - Maintains a memory-safe circular JPEG ring buffer (~15 frames = 1.5s @ 10fps) sampled
 *   from the live OpenGL preview whenever Live Photos is ON.
 * - On shutter press, locks the pre-shutter buffer, captures ~1.5s of post-shutter frames,
 *   and encodes a smooth companion MP4 sidecar linked 1:1 to the saved JPEG displayName.
 * - In Gallery / Darkroom, users can hold/tap to replay the living 3-second moment,
 *   scrub frames to pick & save a sharper "Key Photo", or export the Live Photo as an MP4!
 */
class LivePhotoController(private val context: Context) {

    private val ringLock = Any()
    private val preBuffer = ArrayDeque<ByteArray>(MAX_PRE_FRAMES + 2)
    private var postBuffer: MutableList<ByteArray>? = null
    private var postTargetCount: Int = 0
    private var onPostComplete: ((List<ByteArray>) -> Unit)? = null

    @Volatile
    var isEnabled: Boolean = false

    companion object {
        private const val TAG = "TrueLensLivePhoto"
        private const val MAX_PRE_FRAMES = 14 // ~1.4s @ 10fps
        private const val POST_FRAMES = 14    // ~1.4s @ 10fps
        private const val EXPORT_FPS = 10

        fun livePhotosDir(context: Context): File =
            File(context.filesDir, "live_photos").apply { if (!exists()) mkdirs() }

        fun sidecarFileForName(context: Context, jpegDisplayName: String): File {
            val base = jpegDisplayName.substringBeforeLast('.')
            return File(livePhotosDir(context), "$base.mp4")
        }

        fun hasLivePhotoSidecar(context: Context, jpegDisplayName: String): Boolean {
            val f = sidecarFileForName(context, jpegDisplayName)
            return f.exists() && f.length() > 1024L
        }

        fun getAllLivePhotoBaseNames(context: Context): Set<String> {
            val dir = livePhotosDir(context)
            return dir.listFiles()
                ?.filter { it.extension.equals("mp4", ignoreCase = true) && it.length() > 1024L }
                ?.map { it.nameWithoutExtension }
                ?.toSet()
                ?: emptySet()
        }

        /** Extracts evenly spaced key-photo candidate bitmaps from a Live Photo sidecar MP4. */
        suspend fun extractKeyPhotoFrames(
            file: File,
            maxFrames: Int = 14
        ): List<Pair<Long, Bitmap>> = withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext emptyList()
            val out = mutableListOf<Pair<Long, Bitmap>>()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(300L) ?: 2500L
                val count = maxFrames.coerceIn(4, 18)
                for (i in 0 until count) {
                    val tMs = (durMs * i) / (count - 1).coerceAtLeast(1)
                    val frame = retriever.getFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) {
                        out.add(tMs to frame)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Key photo frame extract error", e)
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
            out
        }

        /** Saves a chosen Live Photo Key Frame as a fresh JPEG in Pictures/TrueLens. */
        suspend fun saveKeyPhotoFrame(context: Context, frame: Bitmap): Uri? = withContext(Dispatchers.IO) {
            try {
                val out = ByteArrayOutputStream()
                frame.compress(Bitmap.CompressFormat.JPEG, 96, out)
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "TrueLens_KeyPhoto_$stamp.jpg")
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
            } catch (_: Exception) {
                null
            }
        }

        /** Exports a Live Photo companion MP4 into Movies/TrueLens so it can be shared anywhere. */
        suspend fun exportSidecarToMovies(context: Context, sidecar: File): Uri? = withContext(Dispatchers.IO) {
            if (!sidecar.exists()) return@withContext null
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "TrueLens_Live_$stamp.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TrueLens")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val cr = context.contentResolver
                val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
                cr.openOutputStream(uri)?.use { os ->
                    sidecar.inputStream().use { it.copyTo(os) }
                }
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                cr.update(uri, done, null, null)
                uri
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Feeds a live ~10fps preview frame into the circular pre-buffer (and active post-buffer if triggered). */
    fun pushPreviewFrame(bitmap: Bitmap) {
        if (!isEnabled) {
            bitmap.recycle()
            return
        }
        val jpegBytes = try {
            val bos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, bos)
            bos.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        if (jpegBytes == null) return

        var completedFrames: List<ByteArray>? = null
        var callbackToFire: ((List<ByteArray>) -> Unit)? = null

        synchronized(ringLock) {
            if (preBuffer.size >= MAX_PRE_FRAMES) {
                preBuffer.removeFirst()
            }
            preBuffer.addLast(jpegBytes)

            val post = postBuffer
            if (post != null) {
                post.add(jpegBytes)
                if (post.size >= postTargetCount) {
                    completedFrames = post.toList()
                    callbackToFire = onPostComplete
                    postBuffer = null
                    onPostComplete = null
                }
            }
        }

        val frames = completedFrames
        val cb = callbackToFire
        if (frames != null && cb != null) {
            cb(frames)
        }
    }

    /**
     * Snapshots the 1.5s pre-shutter buffer right when Shutter is tapped, and schedules
     * collection of 1.5s of post-shutter frames. Returns pre-frames immediately and invokes
     * [onAllReady] once the full ~3s sequence (pre + post) is ready to encode.
     */
    fun triggerCaptureSequence(onAllReady: (List<ByteArray>) -> Unit) {
        val preCopy: List<ByteArray>
        synchronized(ringLock) {
            preCopy = preBuffer.toList()
            postBuffer = mutableListOf()
            postTargetCount = POST_FRAMES
            onPostComplete = { postFrames ->
                onAllReady(preCopy + postFrames)
            }
        }
    }

    fun clearBuffer() {
        synchronized(ringLock) {
            preBuffer.clear()
            postBuffer = null
            onPostComplete = null
        }
    }

    /** Encodes a list of JPEG byte frames directly to a compact local H.264 MP4 sidecar file. */
    suspend fun encodeSidecarMp4(jpegFrames: List<ByteArray>, outputFile: File): Boolean =
        withContext(Dispatchers.Default) {
            if (jpegFrames.size < 3) return@withContext false
            val first = BitmapFactory.decodeByteArray(jpegFrames.first(), 0, jpegFrames.first().size)
                ?: return@withContext false
            val width = (first.width / 16).coerceAtLeast(2) * 16
            val height = (first.height / 16).coerceAtLeast(2) * 16
            first.recycle()

            var codec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, 3_500_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, EXPORT_FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()

                if (outputFile.exists()) outputFile.delete()
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var trackIndex = -1
                var muxerStarted = false
                val bufferInfo = MediaCodec.BufferInfo()
                val frameDurationUs = 1_000_000L / EXPORT_FPS
                val yuvSize = width * height * 3 / 2
                val pixels = IntArray(width * height)

                for ((index, bytes) in jpegFrames.withIndex()) {
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    val scaled = if (decoded.width != width || decoded.height != height) {
                        Bitmap.createScaledBitmap(decoded, width, height, true).also { decoded.recycle() }
                    } else decoded
                    scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                    scaled.recycle()

                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val image = codec.getInputImage(inIdx)
                        if (image != null) {
                            fillYuvImageFromArgb(image, pixels, width, height)
                            image.close()
                        }
                        val ptsUs = index * frameDurationUs
                        val flags = if (index == jpegFrames.lastIndex) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(inIdx, 0, yuvSize, ptsUs, flags)
                    }

                    var outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        } else if (outIdx >= 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && muxerStarted && bufferInfo.size > 0 &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                        outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
                    }
                }

                // Drain remaining frames
                var drainAttempts = 0
                while (drainAttempts < 40) {
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && muxerStarted && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outIdx, false)
                        if (eos) break
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else {
                        drainAttempts++
                    }
                }
                muxerStarted && outputFile.exists() && outputFile.length() > 1024L
            } catch (e: Exception) {
                Log.w(TAG, "Live Photo encode error", e)
                false
            } finally {
                try { codec?.stop(); codec?.release() } catch (_: Exception) {}
                try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
            }
        }

    /**
     * Writes exact Rec.601 Y/U/V planes into MediaCodec's [android.media.Image], automatically
     * adapting to both planar (I420) and semi-planar (NV12/NV21) hardware encoders with zero tint!
     */
    private fun fillYuvImageFromArgb(image: android.media.Image, argb: IntArray, width: Int, height: Int) {
        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRowStride = planes[0].rowStride
        val yPixStride = planes[0].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixStride = planes[2].pixelStride

        for (j in 0 until height) {
            val yRowOff = j * yRowStride
            val uvRow = j shr 1
            val uRowOff = uvRow * uRowStride
            val vRowOff = uvRow * vRowStride
            for (i in 0 until width) {
                val p = argb[j * width + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                val yPos = yRowOff + i * yPixStride
                if (yPos < yBuf.capacity()) yBuf.put(yPos, y.toByte())
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val uvCol = i shr 1
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val uPos = uRowOff + uvCol * uPixStride
                    val vPos = vRowOff + uvCol * vPixStride
                    if (uPos < uBuf.capacity()) uBuf.put(uPos, u.toByte())
                    if (vPos < vBuf.capacity()) vBuf.put(vPos, v.toByte())
                }
            }
        }
    }
}
