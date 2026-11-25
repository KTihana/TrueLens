package com.truelens.camera.pose

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Processes pose reference photos into:
 * 1) Subject Cutouts (offline Google ML Kit Subject Segmentation — strips busy background)
 * 2) Clean Neon Outlines (Sobel edge silhouette for clutter-free tracing)
 *
 * Strictly used on the viewfinder reference guide — never touches the user's captured photo.
 */
object PoseOverlayProcessor {

    private const val TAG = "PoseOverlayProcessor"

    suspend fun extractSubjectCutout(source: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        try {
            val options = SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .build()
            val segmenter = SubjectSegmentation.getClient(options)
            val inputImage = InputImage.fromBitmap(source, 0)

            suspendCancellableCoroutine<Bitmap?> { cont ->
                segmenter.process(inputImage)
                    .addOnSuccessListener { result ->
                        val fg = result.foregroundBitmap
                        segmenter.close()
                        if (cont.isActive) cont.resume(fg)
                    }
                    .addOnFailureListener { err ->
                        Log.w(TAG, "ML Kit Subject Segmentation unavailable/failed: ${err.message}")
                        segmenter.close()
                        if (cont.isActive) cont.resume(null)
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Subject cutout error", e)
            null
        }
    }

    /**
     * Generates a crisp transparent-background glowing amber/white pose silhouette
     * using a fast Sobel edge operator.
     */
    suspend fun generateOutlineBitmap(source: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val maxSide = 720
        val scale = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
        val w = (source.width * scale).toInt().coerceAtLeast(16)
        val h = (source.height * scale).toInt().coerceAtLeast(16)
        val scaled = if (scale < 0.99f) {
            Bitmap.createScaledBitmap(source, w, h, true)
        } else {
            source
        }

        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== source) scaled.recycle()

        val luma = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            if (a < 30) {
                luma[i] = 0
            } else {
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                luma[i] = (r * 77 + g * 150 + b * 29) ushr 8
            }
        }

        val out = IntArray(w * h)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val tl = luma[(y - 1) * w + (x - 1)]
                val tc = luma[(y - 1) * w + x]
                val tr = luma[(y - 1) * w + (x + 1)]
                val ml = luma[row + (x - 1)]
                val mr = luma[row + (x + 1)]
                val bl = luma[(y + 1) * w + (x - 1)]
                val bc = luma[(y + 1) * w + x]
                val br = luma[(y + 1) * w + (x + 1)]

                val gx = -tl + tr - 2 * ml + 2 * mr - bl + br
                val gy = -tl - 2 * tc - tr + bl + 2 * bc + br
                val mag = sqrt((gx * gx + gy * gy).toDouble()).toInt()

                if (mag > 68) {
                    val alpha = ((mag - 68) * 3).coerceIn(0, 255)
                    // Crisp warm amber-white outline
                    out[row + x] = Color.argb(alpha, 255, 210, 90)
                } else {
                    out[row + x] = Color.TRANSPARENT
                }
            }
        }

        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }
}
