package com.truelens.camera.grading

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Android 14+ (API 34+) Ultra HDR (ISO 21496-1 / JPEG_R) Gainmap Encoder.
 *
 * Generates a physically smooth, skin-tone-safe luminance Gain Map from bright specular
 * highlights (sunlight, reflections, sky glow, night lights) and attaches it via [Bitmap.setGainmap].
 *
 * - On modern HDR OLED phones (Android 14+, Pixel, Galaxy, iOS ISO 21496-1): highlights glow with
 *   lifelike dynamic range headroom while faces and shadows stay natural and grounded.
 * - On legacy SDR displays/apps: opens cleanly as a standard 100% backwards-compatible JPEG.
 */
object UltraHdrEncoder {

    private const val TAG = "TrueLensUltraHdr"

    /**
     * Compresses [bitmap] to JPEG bytes, automatically attaching an Ultra HDR Gainmap on API 34+
     * when [enableUltraHdr] is true and the scene contains genuine bright highlight headroom.
     */
    fun compressJpegWithOptionalGainmap(
        bitmap: Bitmap,
        quality: Int = 96,
        enableUltraHdr: Boolean = true,
        maxHdrBoost: Float = 2.4f
    ): ByteArray {
        val out = ByteArrayOutputStream()
        if (enableUltraHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                attachGainmapApi34(bitmap, maxHdrBoost)
            } catch (t: Throwable) {
                Log.w(TAG, "Ultra HDR Gainmap skipped, falling back to standard JPEG: ${t.message}")
            }
        }
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(70, 100), out)
        return out.toByteArray()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun attachGainmapApi34(source: Bitmap, maxHdrBoost: Float) {
        val w = source.width
        val h = source.height
        if (w < 32 || h < 32) return

        // Quarter-resolution monochrome Gainmap (super fast & tiny ~40-80 KB overhead)
        val scale = if (maxOf(w, h) >= 1200) 4 else 2
        val gw = (w / scale).coerceAtLeast(16)
        val gh = (h / scale).coerceAtLeast(16)
        val small = Bitmap.createScaledBitmap(source, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        small.recycle()

        val rawGain = FloatArray(gw * gh)
        var highlightCount = 0
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val maxC = maxOf(r, maxOf(g, b))

            // Only boost true specular / bright sky / light-source highlights (Y > 0.74 or peak > 0.84)
            val peak = maxOf(y, maxC * 0.92f)
            if (peak > 0.74f) {
                val t = ((peak - 0.74f) / 0.26f).coerceIn(0f, 1f)
                // Smooth cubic Hermite ramp
                var gain = t * t * (3f - 2f * t)
                // Protect warm mid-bright skin tones from glowing unnaturally on OLED displays
                val isWarmSkin = r > g && g > b && (r - b) > 0.08f && (r - b) < 0.42f && y < 0.88f
                if (isWarmSkin) {
                    gain *= 0.30f
                }
                rawGain[i] = gain
                if (gain > 0.15f) highlightCount++
            }
        }

        // Skip gainmap on dark/flat scenes with essentially zero highlights
        if (highlightCount < (gw * gh) / 400) return

        // Smooth the gainmap with 2 fast separable 3x3 box passes so HDR transitions have zero halos
        val tmp = FloatArray(gw * gh)
        repeat(2) {
            for (y in 0 until gh) {
                val row = y * gw
                for (x in 0 until gw) {
                    val xL = (x - 1).coerceAtLeast(0)
                    val xR = (x + 1).coerceAtMost(gw - 1)
                    tmp[row + x] = (rawGain[row + xL] + rawGain[row + x] + rawGain[row + xR]) / 3f
                }
            }
            for (y in 0 until gh) {
                val rU = (y - 1).coerceAtLeast(0) * gw
                val rC = y * gw
                val rD = (y + 1).coerceAtMost(gh - 1) * gw
                for (x in 0 until gw) {
                    rawGain[rC + x] = (tmp[rU + x] + tmp[rC + x] + tmp[rD + x]) / 3f
                }
            }
        }

        val gainAlpha = ByteArray(gw * gh) { i ->
            (rawGain[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        val gainBitmap = Bitmap.createBitmap(gw, gh, Bitmap.Config.ALPHA_8)
        gainBitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(gainAlpha))

        val boost = maxHdrBoost.coerceIn(1.5f, 4.0f)
        val gainmap = Gainmap(gainBitmap).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(boost, boost, boost)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0.01f, 0.01f, 0.01f)
            setEpsilonHdr(0.01f, 0.01f, 0.01f)
            setMinDisplayRatioForHdrTransition(1f)
            setDisplayRatioForFullHdr(boost)
        }
        source.gainmap = gainmap
    }
}
