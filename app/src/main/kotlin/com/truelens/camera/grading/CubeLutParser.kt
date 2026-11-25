package com.truelens.camera.grading

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.floor

/**
 * Standard Adobe/DaVinci .cube 3D LUT representation with trilinear interpolation.
 */
data class CubeLut(
    val name: String,
    val size: Int,
    val data: FloatArray // size * size * size * 3 (RGB in 0..1)
) {
    /**
     * Evaluates the 3D LUT for a single RGB pixel in [0, 1] using trilinear interpolation.
     */
    fun lookup(r: Float, g: Float, b: Float, outRgb: FloatArray) {
        val maxIdx = size - 1
        val rf = r.coerceIn(0f, 1f) * maxIdx
        val gf = g.coerceIn(0f, 1f) * maxIdx
        val bf = b.coerceIn(0f, 1f) * maxIdx

        val r0 = floor(rf).toInt().coerceIn(0, maxIdx)
        val g0 = floor(gf).toInt().coerceIn(0, maxIdx)
        val b0 = floor(bf).toInt().coerceIn(0, maxIdx)
        val r1 = (r0 + 1).coerceAtMost(maxIdx)
        val g1 = (g0 + 1).coerceAtMost(maxIdx)
        val b1 = (b0 + 1).coerceAtMost(maxIdx)

        val dr = rf - r0
        val dg = gf - g0
        val db = bf - b0

        fun sample(ri: Int, gi: Int, bi: Int, c: Int): Float {
            val idx = ((bi * size + gi) * size + ri) * 3 + c
            return if (idx in data.indices) data[idx] else 0f
        }

        for (c in 0..2) {
            val c000 = sample(r0, g0, b0, c)
            val c100 = sample(r1, g0, b0, c)
            val c010 = sample(r0, g1, b0, c)
            val c110 = sample(r1, g1, b0, c)
            val c001 = sample(r0, g0, b1, c)
            val c101 = sample(r1, g0, b1, c)
            val c011 = sample(r0, g1, b1, c)
            val c111 = sample(r1, g1, b1, c)

            val c00 = c000 + (c100 - c000) * dr
            val c10 = c010 + (c110 - c010) * dr
            val c01 = c001 + (c101 - c001) * dr
            val c11 = c011 + (c111 - c011) * dr

            val c0 = c00 + (c10 - c00) * dg
            val c1 = c01 + (c11 - c01) * dg

            outRgb[c] = (c0 + (c1 - c0) * db).coerceIn(0f, 1f)
        }
    }

    /**
     * Packs the 3D LUT into a 2D horizontal strip Bitmap (width = size*size, height = size)
     * for 60fps OpenGL ES 2.0 sampling!
     */
    fun toStripBitmap(): Bitmap {
        val w = size * size
        val h = size
        val pixels = IntArray(w * h)
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val idx = ((b * size + g) * size + r) * 3
                    val ro = (data[idx].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    val go = (data[idx + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    val bo = (data[idx + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    pixels[g * w + b * size + r] = (0xFF shl 24) or (ro shl 16) or (go shl 8) or bo
                }
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}

object CubeLutParser {

    suspend fun parseFromUri(context: Context, uri: Uri, fallbackName: String = "Custom LUT"): CubeLut? =
        withContext(Dispatchers.IO) {
            try {
                var title = fallbackName
                var lutSize = 0
                val values = ArrayList<Float>(33 * 33 * 33 * 3)

                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).useLines { lines ->
                        for (rawLine in lines) {
                            val line = rawLine.trim()
                            if (line.isEmpty() || line.startsWith("#")) continue
                            when {
                                line.startsWith("TITLE", ignoreCase = true) -> {
                                    title = line.substringAfter("TITLE").trim().trim('"').ifBlank { fallbackName }
                                }
                                line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                                    lutSize = line.substringAfter("LUT_3D_SIZE").trim().toIntOrNull() ?: 0
                                }
                                line.startsWith("DOMAIN_", ignoreCase = true) -> continue
                                else -> {
                                    val parts = line.split(Regex("\\s+"))
                                    if (parts.size >= 3) {
                                        val r = parts[0].toFloatOrNull()
                                        val g = parts[1].toFloatOrNull()
                                        val b = parts[2].toFloatOrNull()
                                        if (r != null && g != null && b != null) {
                                            values.add(r)
                                            values.add(g)
                                            values.add(b)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val expected = lutSize * lutSize * lutSize * 3
                if (lutSize in 2..65 && values.size >= expected) {
                    val arr = FloatArray(expected) { values[it] }
                    CubeLut(name = title, size = lutSize, data = arr)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
}
