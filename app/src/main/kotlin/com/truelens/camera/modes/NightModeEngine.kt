package com.truelens.camera.modes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.truelens.camera.grading.BitmapGrader
import com.truelens.camera.grading.CubeLut
import com.truelens.camera.grading.DateStampStyle
import com.truelens.camera.grading.LightroomGrade
import com.truelens.camera.grading.PhotoFrameRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Pure Optical Multi-Frame Night Mode Stacking Engine (Zero-AI).
 *
 * Stacks 4–10 handheld frames using robust motion-rejecting optical averaging:
 * pixels consistent with the reference frame are averaged to cut sensor read-noise by ~sqrt(N),
 * while moving edges that deviate strongly from the reference frame are automatically down-weighted
 * to prevent ghosting — zero synthetic AI textures, zero beauty smoothing.
 */
object NightModeEngine {

    suspend fun stackAndSave(
        context: Context,
        frames: List<Bitmap>,
        grade: LightroomGrade,
        lut: CubeLut? = null,
        dateStampStyle: DateStampStyle = DateStampStyle.OFF
    ): Uri? = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext null

        val stacked = stackFramesOptical(frames) ?: return@withContext null
        val graded = if (!grade.isNeutral || lut != null) {
            BitmapGrader.applyGrade(stacked, grade, lut).also {
                if (it !== stacked) stacked.recycle()
            }
        } else stacked

        val stamped = if (dateStampStyle != DateStampStyle.OFF) {
            PhotoFrameRenderer.applyDateStamp(graded, dateStampStyle).also {
                if (it !== graded) graded.recycle()
            }
        } else graded

        val out = ByteArrayOutputStream()
        stamped.compress(Bitmap.CompressFormat.JPEG, 96, out)
        stamped.recycle()

        withContext(Dispatchers.IO) {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "TrueLens_Night_$stamp.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TrueLens")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val cr = context.contentResolver
                val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                cr.openOutputStream(uri)?.use { it.write(out.toByteArray()) }
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                cr.update(uri, done, null, null)
                uri
            } catch (_: Exception) {
                null
            }
        }
    }

    internal fun stackFramesOptical(frames: List<Bitmap>): Bitmap? {
        if (frames.isEmpty()) return null
        val base = frames.first()
        val w = base.width
        val h = base.height
        val count = w * h

        val refPixels = IntArray(count)
        base.getPixels(refPixels, 0, w, 0, 0, w, h)

        val sumR = FloatArray(count)
        val sumG = FloatArray(count)
        val sumB = FloatArray(count)
        val sumW = FloatArray(count)

        for (i in 0 until count) {
            val p = refPixels[i]
            sumR[i] = ((p shr 16) and 0xFF).toFloat()
            sumG[i] = ((p shr 8) and 0xFF).toFloat()
            sumB[i] = (p and 0xFF).toFloat()
            sumW[i] = 1f
        }

        val temp = IntArray(count)
        for (fIdx in 1 until frames.size) {
            val f = frames[fIdx]
            val aligned = if (f.width != w || f.height != h) {
                Bitmap.createScaledBitmap(f, w, h, true)
            } else f
            aligned.getPixels(temp, 0, w, 0, 0, w, h)
            if (aligned !== f) aligned.recycle()

            for (i in 0 until count) {
                val p = temp[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val rp = refPixels[i]
                val rr = (rp shr 16) and 0xFF
                val rg = (rp shr 8) and 0xFF
                val rb = rp and 0xFF

                // Robust motion-rejection weight (Huber-style): rejects ghosting while stacking stationary noise
                val diff = (abs(r - rr) + abs(g - rg) + abs(b - rb)) / 3f
                val weight = when {
                    diff <= 22f -> 1.0f
                    diff <= 52f -> 1.0f - (diff - 22f) / 30f
                    else -> 0.0f
                }
                if (weight > 0.01f) {
                    sumR[i] += r * weight
                    sumG[i] += g * weight
                    sumB[i] += b * weight
                    sumW[i] += weight
                }
            }
        }

        // Gentle shadow lift (+0.18 EV in deep shadows) & natural highlight protection
        val outPixels = IntArray(count)
        for (i in 0 until count) {
            val invW = 1f / sumW[i].coerceAtLeast(1f)
            var r = (sumR[i] * invW) / 255f
            var g = (sumG[i] * invW) / 255f
            var b = (sumB[i] * invW) / 255f

            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val shadowBoost = 1f + 0.22f * (1f - luma) * (1f - luma)
            r = (r * shadowBoost).coerceIn(0f, 1f)
            g = (g * shadowBoost).coerceIn(0f, 1f)
            b = (b * shadowBoost).coerceIn(0f, 1f)

            outPixels[i] = (0xFF shl 24) or
                ((r * 255f).toInt() shl 16) or
                ((g * 255f).toInt() shl 8) or
                (b * 255f).toInt()
        }

        return Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
