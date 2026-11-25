package com.truelens.camera.grading

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * High-precision pixel grader mirroring the OpenGL ES LiveGradingRenderer shader math.
 * Supports 13 Lightroom sliders + HSL Color Mixer + 3-Point Tone Curve + .cube 3D LUTs
 * + Pure Geometric Optical Keystone Straightening.
 */
object BitmapGrader {

    fun applyGrade(source: Bitmap, grade: LightroomGrade, lut: CubeLut? = null): Bitmap {
        if (grade.isNeutral && lut == null) return source.copy(Bitmap.Config.ARGB_8888, false)

        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val exposureMult = 2.0f.pow(grade.exposure)
        val contrastFactor = 1.0f + grade.contrast * 0.85f
        val tempShift = grade.temperature * 0.22f
        val tintShift = grade.tint * 0.18f
        val satFactor = 1.0f + grade.saturation
        val vibAmount = grade.vibrance * 0.85f
        val clarityAmount = grade.clarity * 0.28f
        val vignetteAmount = grade.vignette
        val grainAmount = grade.grain * 0.09f
        val hasHsl = grade.hslRedSat != 0f || grade.hslSkinLum != 0f ||
            grade.hslGreenSat != 0f || grade.hslBlueSat != 0f || grade.hslBlueLum != 0f
        val hasCurve = grade.curveShadows != 0f || grade.curveMidtones != 0f || grade.curveHighlights != 0f
        val lutRgb = FloatArray(3)

        val invW = 1.0f / max(1, width - 1)
        val invH = 1.0f / max(1, height - 1)

        var seed = 0x1234567

        for (y in 0 until height) {
            val ny = y * invH - 0.5f
            val rowOffset = y * width
            for (x in 0 until width) {
                val idx = rowOffset + x
                val px = pixels[idx]
                var r = ((px ushr 16) and 0xFF) / 255.0f
                var g = ((px ushr 8) and 0xFF) / 255.0f
                var b = (px and 0xFF) / 255.0f

                // 1. Exposure (EV stops)
                r *= exposureMult
                g *= exposureMult
                b *= exposureMult

                // 2. White balance (Temperature + Tint)
                r *= (1.0f + tempShift)
                b *= (1.0f - tempShift)
                g *= (1.0f - tintShift * 0.5f)
                r *= (1.0f + tintShift * 0.25f)
                b *= (1.0f + tintShift * 0.25f)

                // 3. Highlights & Shadows recovery
                val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1.5f)
                if (grade.shadows != 0f) {
                    val shadowMask = (1.0f - luma.coerceIn(0f, 1f)).let { it * it }
                    val boost = grade.shadows * 0.55f * shadowMask
                    r += boost
                    g += boost
                    b += boost
                }
                if (grade.highlights != 0f) {
                    val highMask = luma.coerceIn(0f, 1f).let { it * it }
                    val shift = grade.highlights * 0.55f * highMask
                    r += shift
                    g += shift
                    b += shift
                }

                // 4. Whites & Blacks
                if (grade.blacks != 0f) {
                    val blackMask = (1.0f - luma.coerceIn(0f, 1f)).let { it * it * it }
                    val shift = grade.blacks * 0.30f * blackMask
                    r += shift
                    g += shift
                    b += shift
                }
                if (grade.whites != 0f) {
                    val whiteMask = luma.coerceIn(0f, 1f).let { it * it * it }
                    val shift = grade.whites * 0.30f * whiteMask
                    r += shift
                    g += shift
                    b += shift
                }

                // 5. Contrast + Clarity micro-curve around 0.5 midgray
                val totalContrast = contrastFactor + clarityAmount
                r = (r - 0.5f) * totalContrast + 0.5f
                g = (g - 0.5f) * totalContrast + 0.5f
                b = (b - 0.5f) * totalContrast + 0.5f

                // 6. Vibrance & Saturation
                val curLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val sat = maxC - minC
                val vibFactor = 1.0f + vibAmount * (1.0f - sat.coerceIn(0f, 1f))
                val combinedSat = (satFactor * vibFactor).coerceAtLeast(0f)
                r = curLuma + (r - curLuma) * combinedSat
                g = curLuma + (g - curLuma) * combinedSat
                b = curLuma + (b - curLuma) * combinedSat

                // 6B. 3-Point Tone Curve (Shadows / Midtones / Highlights)
                if (hasCurve) {
                    val cl = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                    val wS = (1f - cl) * (1f - cl)
                    val wM = 4f * cl * (1f - cl)
                    val wH = cl * cl
                    val delta = grade.curveShadows * 0.45f * wS +
                        grade.curveMidtones * 0.45f * wM +
                        grade.curveHighlights * 0.45f * wH
                    r += delta; g += delta; b += delta
                }

                // 6C. HSL Color Mixer (Red / Skin Orange / Green Foliage / Blue Sky)
                if (hasHsl) {
                    val rW = (r - max(g, b)).coerceAtLeast(0f)
                    val skinW = (min(r, g * 1.25f) - b).coerceAtLeast(0f)
                    val gW = (g - max(r, b)).coerceAtLeast(0f)
                    val bW = (b - max(r, g)).coerceAtLeast(0f)
                    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val satBoost = 1f + rW * grade.hslRedSat + gW * grade.hslGreenSat + bW * grade.hslBlueSat
                    r = l + (r - l) * satBoost.coerceAtLeast(0f)
                    g = l + (g - l) * satBoost.coerceAtLeast(0f)
                    b = l + (b - l) * satBoost.coerceAtLeast(0f)
                    val lumShift = skinW * grade.hslSkinLum * 0.45f + bW * grade.hslBlueLum * 0.45f
                    r += lumShift; g += lumShift; b += lumShift
                }

                // 6D. Optional .cube 3D LUT Lookup
                if (lut != null) {
                    lut.lookup(r, g, b, lutRgb)
                    r = lutRgb[0]; g = lutRgb[1]; b = lutRgb[2]
                }

                // 7. Optical Vignette
                if (vignetteAmount > 0.001f) {
                    val nx = x * invW - 0.5f
                    val dist = sqrt(nx * nx + ny * ny)
                    val vig = (1.0f - vignetteAmount * dist * dist * 2.2f).coerceIn(0.15f, 1.0f)
                    r *= vig
                    g *= vig
                    b *= vig
                }

                // 8. Subtle Organic Film Grain
                if (grainAmount > 0.0005f) {
                    seed = seed * 1664525 + 1013904223
                    val noise = (((seed ushr 16) and 0xFF) / 127.5f - 1.0f) * grainAmount
                    r += noise
                    g += noise
                    b += noise
                }

                val ri = (r.coerceIn(0f, 1f) * 255.0f + 0.5f).toInt()
                val gi = (g.coerceIn(0f, 1f) * 255.0f + 0.5f).toInt()
                val bi = (b.coerceIn(0f, 1f) * 255.0f + 0.5f).toInt()
                pixels[idx] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /**
     * Pure geometric Optical Perspective / Keystone Straightener (zero AI).
     * Fixes leaning buildings & stairs via 4-point homography + gentle auto-crop.
     */
    fun applyKeystonePerspective(
        source: Bitmap,
        verticalTilt: Float,   // -1f .. +1f
        horizontalTilt: Float, // -1f .. +1f
        rotationDeg: Float     // -15f .. +15f
    ): Bitmap {
        if (abs(verticalTilt) < 0.005f && abs(horizontalTilt) < 0.005f && abs(rotationDeg) < 0.1f) {
            return source
        }
        val w = source.width.toFloat()
        val h = source.height.toFloat()
        val srcPts = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)

        val vInset = verticalTilt * w * 0.18f
        val hInset = horizontalTilt * h * 0.18f

        val dstPts = floatArrayOf(
            if (vInset > 0) vInset else 0f, if (hInset > 0) hInset else 0f,
            if (vInset > 0) w - vInset else w, if (hInset < 0) -hInset else 0f,
            if (vInset < 0) w + vInset else w, if (hInset < 0) h + hInset else h,
            if (vInset < 0) -vInset else 0f, if (hInset > 0) h - hInset else h
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
        if (abs(rotationDeg) >= 0.1f) {
            matrix.postRotate(rotationDeg, w / 2f, h / 2f)
        }
        // Subtle zoom-in to eliminate empty wedge corners after keystone correction
        val zoom = 1f + (abs(verticalTilt) + abs(horizontalTilt)) * 0.22f + abs(rotationDeg) * 0.015f
        matrix.postScale(zoom, zoom, w / 2f, h / 2f)

        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}
