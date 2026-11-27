package com.truelens.camera.modes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.truelens.camera.grading.BitmapGrader
import com.truelens.camera.grading.CubeLut
import com.truelens.camera.grading.LightroomGrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

enum class LongExposureStyle(val label: String) {
    SILKY_WATER("Silky Motion"),
    LIGHT_TRAILS("Light Trails")
}

data class LongExposureState(
    val isCapturing: Boolean = false,
    val durationSec: Int = 4,
    val style: LongExposureStyle = LongExposureStyle.SILKY_WATER,
    val progress: Float = 0f,
    val accumulatedFrames: Int = 0
) {
    companion object {
        val DURATION_OPTIONS = listOf(2, 4, 8, 15)
    }
}

/**
 * Pure optical Long Exposure / Bulb synthesizer.
 * - Silky Motion: exact linear averaging across frames (smooths waterfalls, clouds, crowds)
 * - Light Trails: maximum-luminance accumulation (car tail-lights, star trails, neon)
 */
object LongExposureEngine {

    suspend fun blendAndSave(
        context: Context,
        frames: List<Bitmap>,
        style: LongExposureStyle,
        grade: LightroomGrade,
        lut: CubeLut? = null
    ): Uri? = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext null

        val ref = frames.first()
        val w = ref.width
        val h = ref.height
        val size = w * h
        val count = frames.size

        val rAcc = FloatArray(size)
        val gAcc = FloatArray(size)
        val bAcc = FloatArray(size)
        val wAcc = FloatArray(size)
        val rowBuf = IntArray(size)

        for (i in 0 until count) {
            val bmp = if (frames[i].width != w || frames[i].height != h) {
                Bitmap.createScaledBitmap(frames[i], w, h, true)
            } else {
                frames[i]
            }
            // Anti-shake alignment against Frame #1 cancels tripod wind/vibration!
            val (dx, dy) = if (i == 0) 0 to 0 else AstroSkyEngine.estimateFrameShift(ref, bmp, maxShiftPx = 20)
            bmp.getPixels(rowBuf, 0, w, 0, 0, w, h)
            if (bmp !== frames[i]) bmp.recycle()

            if (style == LongExposureStyle.SILKY_WATER) {
                for (y in 0 until h) {
                    val sy = y + dy
                    if (sy !in 0 until h) continue
                    val dRow = y * w
                    val sRow = sy * w
                    for (x in 0 until w) {
                        val sx = x + dx
                        if (sx !in 0 until w) continue
                        val c = rowBuf[sRow + sx]
                        val p = dRow + x
                        rAcc[p] += ((c ushr 16) and 0xFF)
                        gAcc[p] += ((c ushr 8) and 0xFF)
                        bAcc[p] += (c and 0xFF)
                        wAcc[p] += 1f
                    }
                }
            } else {
                // Light Trails (Anti-shake aligned Max-lighten blend)
                for (y in 0 until h) {
                    val sy = y + dy
                    if (sy !in 0 until h) continue
                    val dRow = y * w
                    val sRow = sy * w
                    for (x in 0 until w) {
                        val sx = x + dx
                        if (sx !in 0 until w) continue
                        val c = rowBuf[sRow + sx]
                        val p = dRow + x
                        val r = ((c ushr 16) and 0xFF).toFloat()
                        val g = ((c ushr 8) and 0xFF).toFloat()
                        val b = (c and 0xFF).toFloat()
                        rAcc[p] = max(rAcc[p], r)
                        gAcc[p] = max(gAcc[p], g)
                        bAcc[p] = max(bAcc[p], b)
                        wAcc[p] = 1f
                    }
                }
            }
        }

        val outPixels = IntArray(size) { p ->
            val inv = if (style == LongExposureStyle.SILKY_WATER) 1f / wAcc[p].coerceAtLeast(1f) else 1f
            val r = (rAcc[p] * inv).toInt().coerceIn(0, 255)
            val g = (gAcc[p] * inv).toInt().coerceIn(0, 255)
            val b = (bAcc[p] * inv).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val blended = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPixels, 0, w, 0, 0, w, h)
        }
        val finalBmp = if (!grade.isNeutral || lut != null) {
            BitmapGrader.applyGrade(blended, grade, lut).also { blended.recycle() }
        } else {
            blended
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = ByteArrayOutputStream()
        finalBmp.compress(Bitmap.CompressFormat.JPEG, 96, out)
        finalBmp.recycle()

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "TrueLens_LongExp_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TrueLens")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null
        context.contentResolver.openOutputStream(uri)?.use { it.write(out.toByteArray()) }
        uri
    }
}
