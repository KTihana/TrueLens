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
import kotlin.math.abs

/**
 * Pure-optics Super Macro Focus Stacker.
 * Evaluates local Laplacian sharpness per pixel across a multi-plane focus sweep
 * and selects the sharpest focal plane at every point — zero AI hallucination.
 */
object FocusStacker {

    suspend fun stackAndSave(
        context: Context,
        frames: List<Bitmap>,
        grade: LightroomGrade,
        lut: CubeLut? = null
    ): Uri? = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext null

        val w = frames.first().width
        val h = frames.first().height
        val count = frames.size

        if (count == 1) {
            val graded = BitmapGrader.applyGrade(frames[0], grade, lut)
            return@withContext saveJpeg(context, graded, "TrueLens_MacroStack")
        }

        val allPixels = Array(count) { i ->
            val src = if (frames[i].width != w || frames[i].height != h) {
                Bitmap.createScaledBitmap(frames[i], w, h, true)
            } else {
                frames[i]
            }
            IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        }

        // Compute luminance arrays for fast Laplacian sharpness score
        val lumas = Array(count) { i ->
            val px = allPixels[i]
            IntArray(w * h) { k ->
                val c = px[k]
                (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) ushr 8
            }
        }

        val outPixels = IntArray(w * h)
        System.arraycopy(allPixels[0], 0, outPixels, 0, w * h)

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                var bestIdx = 0
                var bestScore = -1
                for (f in 0 until count) {
                    val l = lumas[f]
                    val center = l[idx]
                    val lap = abs(4 * center - l[idx - 1] - l[idx + 1] - l[idx - w] - l[idx + w])
                    if (lap > bestScore) {
                        bestScore = lap
                        bestIdx = f
                    }
                }
                outPixels[idx] = allPixels[bestIdx][idx]
            }
        }

        val stacked = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPixels, 0, w, 0, 0, w, h)
        }
        val finalBmp = if (!grade.isNeutral || lut != null) {
            BitmapGrader.applyGrade(stacked, grade, lut).also { stacked.recycle() }
        } else {
            stacked
        }
        val uri = saveJpeg(context, finalBmp, "TrueLens_MacroStack")
        finalBmp.recycle()
        uri
    }

    private fun saveJpeg(context: Context, bitmap: Bitmap, prefix: String): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
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
