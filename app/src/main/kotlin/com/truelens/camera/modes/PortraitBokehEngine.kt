package com.truelens.camera.modes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.truelens.camera.grading.BitmapGrader
import com.truelens.camera.grading.CubeLut
import com.truelens.camera.grading.DateStampStyle
import com.truelens.camera.grading.LightroomGrade
import com.truelens.camera.grading.PhotoFrameRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Portrait Mode Optical-Style Bokeh Engine (Adjustable f/1.4 - f/16).
 *
 * Uses on-device ML Kit Subject Segmentation confidence mask to keep the subject tack-sharp
 * while applying smooth multi-pass Gaussian background bokeh scaled smoothly by the chosen
 * f-stop aperture (f/1.4 = dreamy shallow bokeh, f/16 = nearly full sharpness).
 */
object PortraitBokehEngine {

    private const val TAG = "TrueLensPortrait"

    private val segmenter by lazy {
        val opts = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        SubjectSegmentation.getClient(opts)
    }

    /** Maps f/1.4..f/16 aperture to blur radius (in downscaled bokeh buffer pixels). */
    fun apertureToBlurRadius(apertureF: Float): Int {
        val f = apertureF.coerceIn(1.4f, 16.0f)
        // Smooth optical inverse relationship: f/1.4 -> radius 22, f/2.8 -> radius 12, f/8 -> radius 4, f/16 -> 1
        return ((31f / f).toInt()).coerceIn(1, 24)
    }

    suspend fun renderAndSavePortrait(
        context: Context,
        source: Bitmap,
        apertureF: Float,
        grade: LightroomGrade,
        lut: CubeLut? = null,
        dateStampStyle: DateStampStyle = DateStampStyle.OFF
    ): Uri? = withContext(Dispatchers.Default) {
        val blurredPortrait = applyPortraitBokeh(source, apertureF)
        val graded = if (!grade.isNeutral || lut != null) {
            BitmapGrader.applyGrade(blurredPortrait, grade, lut).also {
                if (it !== blurredPortrait) blurredPortrait.recycle()
            }
        } else blurredPortrait

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
                    put(MediaStore.Images.Media.DISPLAY_NAME, "TrueLens_Portrait_$stamp.jpg")
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

    suspend fun applyPortraitBokeh(source: Bitmap, apertureF: Float): Bitmap = withContext(Dispatchers.Default) {
        val w = source.width
        val h = source.height
        val radius = apertureToBlurRadius(apertureF)
        if (radius <= 1) return@withContext source.copy(Bitmap.Config.ARGB_8888, false)

        // Downscale mask input to <= 640px for fast segmentation, then smoothly upsample mask
        val scale = (640f / maxOf(w, h).toFloat()).coerceAtMost(1f)
        val mw = (w * scale).toInt().coerceAtLeast(32)
        val mh = (h * scale).toInt().coerceAtLeast(32)
        val maskInput = if (scale < 0.99f) Bitmap.createScaledBitmap(source, mw, mh, true) else source

        val maskFloat: FloatArray? = suspendCancellableCoroutine { cont ->
            try {
                segmenter.process(InputImage.fromBitmap(maskInput, 0))
                    .addOnSuccessListener { result ->
                        val buf = result.foregroundConfidenceMask
                        if (buf != null) {
                            buf.rewind()
                            val arr = FloatArray(buf.remaining())
                            buf.get(arr)
                            if (cont.isActive) cont.resume(arr)
                        } else {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Subject segmentation fallback to radial optical bokeh: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Segmenter exception", e)
                if (cont.isActive) cont.resume(null)
            }
        }
        if (maskInput !== source) maskInput.recycle()

        // Generate creamy downsampled + 3-pass box-blurred background (approximates true Gaussian optical bokeh!)
        val bw = (w / 4).coerceAtLeast(64)
        val bh = (h / 4).coerceAtLeast(64)
        val small = Bitmap.createScaledBitmap(source, bw, bh, true)
        val smallPx = IntArray(bw * bh)
        small.getPixels(smallPx, 0, bw, 0, 0, bw, bh)
        small.recycle()

        val blurPassRadius = (radius / 2).coerceIn(1, 12)
        repeat(3) {
            boxBlurHorizontal(smallPx, bw, bh, blurPassRadius)
            boxBlurVertical(smallPx, bw, bh, blurPassRadius)
        }
        val blurredSmall = Bitmap.createBitmap(smallPx, bw, bh, Bitmap.Config.ARGB_8888)
        val blurredFull = Bitmap.createScaledBitmap(blurredSmall, w, h, true)
        blurredSmall.recycle()

        val srcPx = IntArray(w * h)
        val blurPx = IntArray(w * h)
        source.getPixels(srcPx, 0, w, 0, 0, w, h)
        blurredFull.getPixels(blurPx, 0, w, 0, 0, w, h)
        blurredFull.recycle()

        val outPx = IntArray(w * h)
        val hasMask = maskFloat != null && maskFloat.size >= mw * mh

        for (y in 0 until h) {
            val my = if (hasMask) ((y.toFloat() / h) * (mh - 1)).toInt().coerceIn(0, mh - 1) else 0
            val ny = (y.toFloat() / h) - 0.45f
            val rowOff = y * w
            val mRowOff = my * mw
            for (x in 0 until w) {
                val fgAlpha: Float = if (hasMask) {
                    val mx = ((x.toFloat() / w) * (mw - 1)).toInt().coerceIn(0, mw - 1)
                    val conf = maskFloat!![mRowOff + mx]
                    // Soft optical feather transition between 0.25 and 0.72 so edges never look harsh
                    ((conf - 0.25f) / 0.47f).coerceIn(0f, 1f)
                } else {
                    // Graceful optical center-portrait falloff if Play Services model hasn't finished downloading yet
                    val nx = (x.toFloat() / w) - 0.5f
                    val d = kotlin.math.hypot(nx.toDouble() * 1.4, ny.toDouble() * 1.1).toFloat()
                    (1f - ((d - 0.26f) / 0.34f)).coerceIn(0f, 1f)
                }

                val idx = rowOff + x
                val sp = srcPx[idx]
                val bp = blurPx[idx]
                if (fgAlpha >= 0.99f) {
                    outPx[idx] = sp
                } else if (fgAlpha <= 0.01f) {
                    outPx[idx] = bp
                } else {
                    val inv = 1f - fgAlpha
                    val r = (((sp shr 16) and 0xFF) * fgAlpha + ((bp shr 16) and 0xFF) * inv).toInt()
                    val g = (((sp shr 8) and 0xFF) * fgAlpha + ((bp shr 8) and 0xFF) * inv).toInt()
                    val b = ((sp and 0xFF) * fgAlpha + (bp and 0xFF) * inv).toInt()
                    outPx[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlurHorizontal(px: IntArray, w: Int, h: Int, radius: Int) {
        val temp = IntArray(w)
        val div = radius * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var rSum = 0; var gSum = 0; var bSum = 0
            for (k in -radius..radius) {
                val p = px[row + k.coerceIn(0, w - 1)]
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (x in 0 until w) {
                temp[x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val rem = px[row + (x - radius).coerceIn(0, w - 1)]
                val add = px[row + (x + radius + 1).coerceIn(0, w - 1)]
                rSum += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                gSum += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                bSum += (add and 0xFF) - (rem and 0xFF)
            }
            System.arraycopy(temp, 0, px, row, w)
        }
    }

    private fun boxBlurVertical(px: IntArray, w: Int, h: Int, radius: Int) {
        val temp = IntArray(h)
        val div = radius * 2 + 1
        for (x in 0 until w) {
            var rSum = 0; var gSum = 0; var bSum = 0
            for (k in -radius..radius) {
                val p = px[k.coerceIn(0, h - 1) * w + x]
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (y in 0 until h) {
                temp[y] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val rem = px[(y - radius).coerceIn(0, h - 1) * w + x]
                val add = px[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                rSum += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                gSum += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                bSum += (add and 0xFF) - (rem and 0xFF)
            }
            for (y in 0 until h) {
                px[y * w + x] = temp[y]
            }
        }
    }
}
