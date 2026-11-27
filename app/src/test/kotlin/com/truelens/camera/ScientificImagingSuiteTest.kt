package com.truelens.camera

import com.truelens.camera.grading.ComputationalDenoiser
import com.truelens.camera.grading.OpticalHdrEngine
import com.truelens.camera.modes.PortraitBokehEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScientificImagingSuiteTest {

    private fun packRgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun luma(p: Int): Double =
        0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)

    @Test
    fun mertensOpticalHdrRecoversBothBlownHighlightsAndCrushedShadows() {
        val w = 128
        val h = 128

        // Frame 0 (-1.5 EV): sky has rich blue gradient (80, 140, 220), shadows crushed near 8
        val under = IntArray(w * h) { idx ->
            val y = idx / w
            if (y < h / 2) packRgb(70, 135, 220) else packRgb(8, 10, 12)
        }
        // Frame 1 (0 EV): sky blown out to 252, midtones ok, shadows dim (30)
        val normal = IntArray(w * h) { idx ->
            val y = idx / w
            if (y < h / 2) packRgb(252, 252, 252) else packRgb(30, 34, 38)
        }
        // Frame 2 (+1.0 EV): sky clipped to 255, shadows richly exposed (115, 125, 135)
        val over = IntArray(w * h) { idx ->
            val y = idx / w
            if (y < h / 2) packRgb(255, 255, 255) else packRgb(115, 125, 135)
        }

        val fused = OpticalHdrEngine.fuseAlignedBracketPixels(
            w = w,
            h = h,
            frames = listOf(under, normal, over),
            refIndex = 1
        )

        val skyPx = fused[(h / 4) * w + (w / 2)]
        val skyR = (skyPx shr 16) and 0xFF
        val skyB = skyPx and 0xFF
        val shadowPx = fused[(h * 3 / 4) * w + (w / 2)]
        val shadowLuma = luma(shadowPx)

        // Sky must recover genuine blue color (B > R) instead of blown-out flat white!
        assertTrue("Expected HDR sky to recover blue channel (R=$skyR, B=$skyB)", skyB > skyR + 25)
        assertTrue("Expected HDR sky not to clip flat white (luma=${luma(skyPx)})", luma(skyPx) < 225.0)
        // Shadows must be lifted well above crushed normal exposure (33)!
        assertTrue("Expected HDR shadows to lift richly above 33 (got $shadowLuma)", shadowLuma > 65.0)
    }

    @Test
    fun agxHighlightRolloffPreservesHueAndLeavesShadowsUntouched() {
        val w = 32
        val h = 32
        val shadow = packRgb(40, 45, 50)
        val sunset = packRgb(255, 165, 60)
        val sky = packRgb(95, 195, 255)

        val px = IntArray(w * h) { i ->
            when (i % 3) {
                0 -> shadow
                1 -> sunset
                else -> sky
            }
        }

        OpticalHdrEngine.applyAgxHighlightRolloffInPlace(px, w, h, strength = 0.65f)

        // Shadows MUST be 100% untouched
        assertEquals("Shadows below rolloff knee must remain untouched", shadow, px[0])

        // Sunset orange must preserve R > G > B hue order without twisting yellow
        val sOut = px[1]
        val sr = (sOut shr 16) and 0xFF
        val sg = (sOut shr 8) and 0xFF
        val sb = sOut and 0xFF
        assertTrue("Sunset hue order R > G > B must hold ($sr, $sg, $sb)", sr > sg && sg > sb)

        // Sky blue must preserve B > G > R hue order without twisting cyan
        val bOut = px[2]
        val br = (bOut shr 16) and 0xFF
        val bg = (bOut shr 8) and 0xFF
        val bb = bOut and 0xFF
        assertTrue("Sky hue order B > G > R must hold ($br, $bg, $bb)", bb > bg && bg > br)
    }

    @Test
    fun linearOpticalDiscBokehBloomsSpecularOrbAndPreventsForegroundBleed() {
        val w = 64
        val h = 64
        val bgDark = packRgb(14, 16, 22)
        val src = IntArray(w * h) { bgDark }

        // Place a small 3x3 bright specular streetlight at (32, 32)
        for (y in 31..33) {
            for (x in 31..33) {
                src[y * w + x] = packRgb(255, 240, 200)
            }
        }
        // Place a bright foreground jacket on the left edge (x < 16) with fgMask = 1.0
        val fgMask = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until 16) {
                src[y * w + x] = packRgb(245, 245, 245)
                fgMask[y * w + x] = 1.0f
            }
        }

        val bokeh = PortraitBokehEngine.renderLinearOpticalDiscBokeh(
            srcPx = src,
            fgMask = fgMask,
            w = w,
            h = h,
            discRadius = 6
        )

        // Specular orb at offset +3px from center must bloom visibly brighter than dark background!
        val orbLuma = luma(bokeh[32 * w + 35])
        assertTrue("Linear-light optical bokeh must bloom specular lights into bright discs (orbLuma=$orbLuma)", orbLuma > 55.0)

        // Background pixel 2px right of foreground jacket (x = 18) must NOT get washed out by foreground bleed!
        val nearFgBgLuma = luma(bokeh[10 * w + 18])
        assertTrue("Foreground bleed guard must keep nearby background dark (got $nearFgBgLuma)", nearFgBgLuma < 85.0)
    }

    @Test
    fun guidedFilterRefinesCoarsePortraitMaskOntoLuminanceEdge() {
        val w = 64
        val h = 64
        // Sharp vertical luminance edge at x = 32 (bright subject left, dark background right)
        val guidePx = IntArray(w * h) { idx ->
            val x = idx % w
            if (x < 32) packRgb(210, 200, 195) else packRgb(25, 28, 32)
        }
        // Deliberately coarse/sloppy mask that spills 3 pixels past x = 32
        val sloppyMask = FloatArray(w * h) { idx ->
            val x = idx % w
            when {
                x < 30 -> 1f
                x < 36 -> 0.5f
                else -> 0f
            }
        }

        val refined = PortraitBokehEngine.refineMaskGuidedFilter(guidePx, sloppyMask, w, h, radius = 5, eps = 0.005f)
        val insideSubject = refined[32 * w + 31]
        val outsideBackground = refined[32 * w + 33]

        // Sloppy input mask had 0.00 separation across x=31..33 (both 0.50); Guided Filter snaps sharply onto luminance edge!
        assertTrue(
            "Guided filter must snap mask tightly onto sharp luminance boundary (in=$insideSubject, out=$outsideBackground)",
            insideSubject - outsideBackground > 0.38f
        )
    }

    @Test
    fun gyroWeightedLuckyBurstRejectsShakenSecondaryFrame() {
        val w = 96
        val h = 96
        // Crisp vertical edge reference
        val ref = IntArray(w * h) { idx ->
            if ((idx % w) < 48) packRgb(30, 30, 30) else packRgb(220, 220, 220)
        }
        // Severely blurred/washed secondary frame (simulating hand-shake blur)
        val shaken = IntArray(w * h) { packRgb(125, 125, 125) }

        val merged = ComputationalDenoiser.mergeBurstPixels(
            w = w,
            h = h,
            refPixels = ref,
            secondaryCount = 1,
            iso = 400,
            secondaryShakeScores = floatArrayOf(4.5f),
            loadSecondaryInto = { _, dst ->
                System.arraycopy(shaken, 0, dst, 0, w * h)
                true
            }
        )

        val edgeContrast = abs(luma(merged[48 * w + 50]) - luma(merged[48 * w + 46]))
        assertTrue(
            "Shaken frame must not wash out crisp reference edge (edgeContrast=$edgeContrast)",
            edgeContrast > 160.0
        )
    }
}
