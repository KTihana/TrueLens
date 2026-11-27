package com.truelens.camera.modes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.truelens.camera.camera.DoubleExpBlendMode
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

/**
 * In-Camera Optical Double Exposure Engine.
 * Blends Shot 1 + Shot 2 cleanly using classic analog Screen, Overlay, Lighten, or Average math.
 */
object DoubleExposureEngine {

    suspend fun blendAndSave(
        context: Context,
        shot1: Bitmap,
        shot2: Bitmap,
        blendMode: DoubleExpBlendMode,
        weightSecond: Float = 0.5f,
        grade: LightroomGrade,
        lut: CubeLut? = null,
        dateStampStyle: DateStampStyle = DateStampStyle.OFF
    ): Uri? = withContext(Dispatchers.Default) {
        val blended = blendBitmaps(shot1, shot2, blendMode, weightSecond)
        val graded = if (!grade.isNeutral || lut != null) {
            BitmapGrader.applyGrade(blended, grade, lut).also {
                if (it !== blended) blended.recycle()
            }
        } else blended
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
                    put(MediaStore.Images.Media.DISPLAY_NAME, "TrueLens_DoubleExp_$stamp.jpg")
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
    }

    internal fun blendBitmaps(
        a: Bitmap,
        b: Bitmap,
        mode: DoubleExpBlendMode,
        w2: Float
    ): Bitmap {
        val w = a.width
        val h = a.height
        val bScaled = if (b.width != w || b.height != h) Bitmap.createScaledBitmap(b, w, h, true) else b
        val count = w * h
        val pxA = IntArray(count)
        val pxB = IntArray(count)
        a.getPixels(pxA, 0, w, 0, 0, w, h)
        bScaled.getPixels(pxB, 0, w, 0, 0, w, h)
        if (bScaled !== b) bScaled.recycle()

        val out = IntArray(count)
        val alpha2 = w2.coerceIn(0.15f, 0.85f)
        val alpha1 = 1f - alpha2

        for (i in 0 until count) {
            val ca = pxA[i]
            val cb = pxB[i]
            val ra = ((ca shr 16) and 0xFF) / 255f
            val ga = ((ca shr 8) and 0xFF) / 255f
            val ba = (ca and 0xFF) / 255f
            val rb = ((cb shr 16) and 0xFF) / 255f
            val gb = ((cb shr 8) and 0xFF) / 255f
            val bb = (cb and 0xFF) / 255f

            val rr: Float; val gg: Float; val bbOut: Float
            when (mode) {
                DoubleExpBlendMode.SCREEN -> {
                    rr = 1f - (1f - ra) * (1f - rb)
                    gg = 1f - (1f - ga) * (1f - gb)
                    bbOut = 1f - (1f - ba) * (1f - bb)
                }
                DoubleExpBlendMode.OVERLAY -> {
                    rr = if (ra < 0.5f) 2f * ra * rb else 1f - 2f * (1f - ra) * (1f - rb)
                    gg = if (ga < 0.5f) 2f * ga * gb else 1f - 2f * (1f - ga) * (1f - gb)
                    bbOut = if (ba < 0.5f) 2f * ba * bb else 1f - 2f * (1f - ba) * (1f - bb)
                }
                DoubleExpBlendMode.LIGHTEN -> {
                    rr = maxOf(ra, rb)
                    gg = maxOf(ga, gb)
                    bbOut = maxOf(ba, bb)
                }
                DoubleExpBlendMode.AVERAGE -> {
                    rr = ra * alpha1 + rb * alpha2
                    gg = ga * alpha1 + gb * alpha2
                    bbOut = ba * alpha1 + bb * alpha2
                }
            }
            out[i] = (0xFF shl 24) or
                ((rr.coerceIn(0f, 1f) * 255f).toInt() shl 16) or
                ((gg.coerceIn(0f, 1f) * 255f).toInt() shl 8) or
                (bbOut.coerceIn(0f, 1f) * 255f).toInt()
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
