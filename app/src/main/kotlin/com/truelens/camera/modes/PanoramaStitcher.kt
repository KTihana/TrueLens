package com.truelens.camera.modes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.truelens.camera.grading.BitmapGrader
import com.truelens.camera.grading.LightroomGrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PanoramaState(
    val isCapturing: Boolean = false,
    val frameCount: Int = 0,
    val accumulatedYawDeg: Float = 0f,
    val isStitching: Boolean = false
)

/**
 * Multi-frame Panorama Stitcher with smooth linear alpha feathering across overlap strips.
 * Pairs with gyroscope yaw tracking and locked exposure/WB so skies never band.
 */
object PanoramaStitcher {

    /**
     * Stitches an ordered sequence of frames (left -> right) using 22% feathered overlap
     * blending, applies the active Lightroom grade, and saves to Pictures/TrueLens.
     */
    suspend fun stitchAndSave(
        context: Context,
        frames: List<Bitmap>,
        grade: LightroomGrade,
        overlapFraction: Float = 0.22f
    ): Uri? = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext null

        val targetHeight = frames.minOf { it.height }.coerceAtMost(1440)
        val scaled = frames.map { bmp ->
            val ratio = targetHeight.toFloat() / bmp.height.toFloat()
            val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
            if (bmp.height != targetHeight) {
                Bitmap.createScaledBitmap(bmp, w, targetHeight, true)
            } else {
                bmp
            }
        }

        if (scaled.size == 1) {
            val graded = BitmapGrader.applyGrade(scaled[0], grade)
            return@withContext saveJpeg(context, graded, "TrueLens_Pano")
        }

        val frameW = scaled[0].width
        val overlapPx = (frameW * overlapFraction).toInt().coerceIn(16, frameW / 2)
        val stride = (frameW - overlapPx).coerceAtLeast(1)
        val totalWidth = frameW + (scaled.size - 1) * stride

        val outPixels = IntArray(totalWidth * targetHeight)
        val firstPixels = IntArray(frameW * targetHeight)
        scaled[0].getPixels(firstPixels, 0, frameW, 0, 0, frameW, targetHeight)

        // Copy first frame cleanly
        for (y in 0 until targetHeight) {
            System.arraycopy(firstPixels, y * frameW, outPixels, y * totalWidth, frameW)
        }

        // Feather-blend each subsequent frame across the overlap band
        val nextPixels = IntArray(frameW * targetHeight)
        for (idx in 1 until scaled.size) {
            val bmp = scaled[idx]
            val bw = bmp.width.coerceAtMost(frameW)
            bmp.getPixels(nextPixels, 0, bw, 0, 0, bw, targetHeight)
            val destStartX = idx * stride

            for (y in 0 until targetHeight) {
                val outRow = y * totalWidth
                val srcRow = y * bw
                for (x in 0 until bw) {
                    val destX = destStartX + x
                    if (destX >= totalWidth) break
                    val srcPx = nextPixels[srcRow + x]
                    if (x < overlapPx) {
                        val alpha = x.toFloat() / overlapPx.toFloat()
                        val inv = 1f - alpha
                        val dstPx = outPixels[outRow + destX]
                        val r = (((dstPx ushr 16) and 0xFF) * inv + ((srcPx ushr 16) and 0xFF) * alpha).toInt()
                        val g = (((dstPx ushr 8) and 0xFF) * inv + ((srcPx ushr 8) and 0xFF) * alpha).toInt()
                        val b = ((dstPx and 0xFF) * inv + (srcPx and 0xFF) * alpha).toInt()
                        outPixels[outRow + destX] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        outPixels[outRow + destX] = srcPx
                    }
                }
            }
        }

        scaled.forEachIndexed { i, b -> if (b !== frames[i]) b.recycle() }

        val stitched = Bitmap.createBitmap(totalWidth, targetHeight, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPixels, 0, totalWidth, 0, 0, totalWidth, targetHeight)
        }
        val finalBmp = if (!grade.isNeutral) {
            BitmapGrader.applyGrade(stitched, grade).also { stitched.recycle() }
        } else {
            stitched
        }

        val uri = saveJpeg(context, finalBmp, "TrueLens_Pano")
        finalBmp.recycle()
        uri
    }

    private fun saveJpeg(context: Context, bitmap: Bitmap, prefix: String): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${prefix}_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TrueLens")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(out.toByteArray()) }
        return uri
    }
}
