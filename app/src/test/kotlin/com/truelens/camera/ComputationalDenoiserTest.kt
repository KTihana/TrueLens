package com.truelens.camera

import com.truelens.camera.grading.ComputationalDenoiser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class ComputationalDenoiserTest {

    private fun packRgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun luma(p: Int): Double =
        0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)

    private fun rmseRgb(a: IntArray, b: IntArray, w: Int, h: Int, border: Int = 12): Double {
        var sumSq = 0.0
        var n = 0
        for (y in border until h - border) {
            val row = y * w
            for (x in border until w - border) {
                val p1 = a[row + x]
                val p2 = b[row + x]
                val dr = ((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF)
                val dg = ((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF)
                val db = (p1 and 0xFF) - (p2 and 0xFF)
                sumSq += (dr * dr + dg * dg + db * db) / 3.0
                n++
            }
        }
        return sqrt(sumSq / n.coerceAtLeast(1))
    }

    @Test
    fun fiveFrameAlignedBurstPlusWaveletDramaticallyReducesHighIsoNoiseWithHandshake() {
        val w = 160
        val h = 160
        // Clean synthetic studio scene: dark shadow region + midtone skin block + sharp edge
        val clean = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                clean[y * w + x] = when {
                    x < 80 && y < 80 -> packRgb(42, 46, 52)    // dim shadow
                    x >= 80 && y < 80 -> packRgb(210, 170, 145) // sharp edge + warm skin tone
                    else -> packRgb(95, 115, 130)               // midtone backdrop
                }
            }
        }

        // Simulate 5 handheld high-ISO burst frames with independent Gaussian noise (sigma ~24)
        // PLUS realistic hand-shake translation shifts up to ±3 pixels!
        val shifts = arrayOf(0 to 0, 2 to -1, -2 to 2, 1 to 2, -3 to -1)
        val burstFrames = Array(5) { frameIdx ->
            val rng = Random(1000L + frameIdx * 77L)
            val (sx, sy) = shifts[frameIdx]
            IntArray(w * h) { idx ->
                val x = idx % w
                val y = idx / w
                val srcX = (x - sx).coerceIn(0, w - 1)
                val srcY = (y - sy).coerceIn(0, h - 1)
                val p = clean[srcY * w + srcX]
                val r = ((p shr 16) and 0xFF) + (rng.nextGaussian() * 24.0).toInt()
                val g = ((p shr 8) and 0xFF) + (rng.nextGaussian() * 24.0).toInt()
                val b = (p and 0xFF) + (rng.nextGaussian() * 24.0).toInt()
                packRgb(r, g, b)
            }
        }

        val rawRmse = rmseRgb(burstFrames[0], clean, w, h)

        // 1. Aligned HDR+ multi-frame merge
        val merged = ComputationalDenoiser.mergeBurstPixels(
            w = w,
            h = h,
            refPixels = burstFrames[0],
            secondaryCount = 4,
            iso = 1600,
            loadSecondaryInto = { secIdx, dst ->
                System.arraycopy(burstFrames[secIdx + 1], 0, dst, 0, w * h)
                true
            }
        )
        val mergedRmse = rmseRgb(merged, clean, w, h)

        // 2. Full Clean Look finishing (2-scale chroma pyramid + 3-octave À-Trous wavelets)
        val fullClean = merged.copyOf()
        ComputationalDenoiser.denoisePixelsInPlace(fullClean, w, h, iso = 1600, mergedFrameCount = 5)
        val cleanRmse = rmseRgb(fullClean, clean, w, h)

        // Verify massive noise reduction:
        // - 5-frame aligned merge alone cuts RMSE dramatically even WITH hand-shake shifts!
        assertTrue(
            "Expected aligned 5-frame merge to cut RMSE significantly (raw=$rawRmse, merged=$mergedRmse)",
            mergedRmse < rawRmse * 0.60
        )
        // - Full Clean Look cuts total noise by >70%!
        assertTrue(
            "Expected full Clean Look (5-frame + wavelet) to cut noise by >70% (raw=$rawRmse, clean=$cleanRmse)",
            cleanRmse < rawRmse * 0.30
        )

        // Verify sharp vertical edge between x=78 and x=82 is preserved crisply (zero plastic smearing!)
        var edgeContrast = 0.0
        for (y in 20 until 60) {
            edgeContrast += abs(luma(fullClean[y * w + 82]) - luma(fullClean[y * w + 78]))
        }
        edgeContrast /= 40.0
        val cleanEdgeContrast = abs(luma(packRgb(210, 170, 145)) - luma(packRgb(42, 46, 52)))
        assertTrue(
            "Sharp optical edge should retain >85% contrast (got $edgeContrast vs $cleanEdgeContrast)",
            edgeContrast > cleanEdgeContrast * 0.85
        )
    }

    @Test
    fun multiFrameWienerMergeRejectsMovingObjectGhosting() {
        val w = 128
        val h = 128
        val bg = packRgb(50, 55, 60)
        val ref = IntArray(w * h) { bg }

        // Frame 1 has a bright moving subject (240, 230, 220) in a 20x20 box — MUST NOT ghost onto ref!
        val moving = IntArray(w * h) { bg }
        for (y in 50 until 70) {
            for (x in 50 until 70) {
                moving[y * w + x] = packRgb(240, 230, 220)
            }
        }

        val merged = ComputationalDenoiser.mergeBurstPixels(
            w = w,
            h = h,
            refPixels = ref,
            secondaryCount = 1,
            iso = 400,
            loadSecondaryInto = { _, dst ->
                System.arraycopy(moving, 0, dst, 0, w * h)
                true
            }
        )

        // Center of moving box must remain dark background (zero ghosting!)
        val centerLuma = luma(merged[60 * w + 60])
        assertTrue(
            "Moving object must be rejected with zero ghosting (expected ~54, got $centerLuma)",
            centerLuma < 65.0
        )
    }

    @Test
    fun singleFrameMultiScaleWaveletCleansChromaAndLumaBlotches() {
        val w = 128
        val h = 128
        val clean = IntArray(w * h) { packRgb(70, 72, 74) }
        val rng = Random(42L)
        val noisy = IntArray(w * h) {
            // Simulate heavy shadow chroma (magenta/green) + luminance noise
            val lNoise = (rng.nextGaussian() * 18.0).toInt()
            val cNoise = (rng.nextGaussian() * 22.0).toInt()
            packRgb(70 + lNoise + cNoise, 72 + lNoise - cNoise, 74 + lNoise + cNoise / 2)
        }

        val before = rmseRgb(noisy, clean, w, h)
        val processed = noisy.copyOf()
        ComputationalDenoiser.denoisePixelsInPlace(processed, w, h, iso = 1600, mergedFrameCount = 1)
        val after = rmseRgb(processed, clean, w, h)

        assertTrue(
            "Single-frame multi-scale wavelet denoiser must cut heavy ISO 1600 noise by >55% (before=$before, after=$after)",
            after < before * 0.45
        )
    }
}
