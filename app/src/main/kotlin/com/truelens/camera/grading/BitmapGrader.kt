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

                // 9. Optional Film Halation Highlight Bloom (CineStill-style red/orange glow on bright highlights)
                if (grade.halation > 0.01f) {
                    val lumaH = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    if (lumaH > 0.68f) {
                        val halo = ((lumaH - 0.68f) / 0.32f).coerceIn(0f, 1f) * grade.halation * 0.42f
                        r += halo
                        g += halo * 0.30f
                        b += halo * 0.08f
                    }
                }

                // 10. Optional Analog Film Light Leaks (5 unique styles matching live OpenGL shader!)
                if (grade.lightLeakStyle > 0 && grade.lightLeakIntensity > 0.01f) {
                    val ux = x * invW
                    val uy = 1f - y * invH
                    val amt = grade.lightLeakIntensity
                    var lr = 0f; var lg = 0f; var lb = 0f
                    when (grade.lightLeakStyle) {
                        1 -> {
                            val t = (ux - 0.04f) * 3.8f
                            val w1 = kotlin.math.exp((-t * t).toDouble()).toFloat() * (0.65f + 0.35f * kotlin.math.sin(uy * Math.PI).toFloat())
                            lr = 1.0f * w1 * 0.72f; lg = 0.36f * w1 * 0.72f; lb = 0.08f * w1 * 0.72f
                        }
                        2 -> {
                            val dx = (ux - 0.92f) * 1.3f; val dy = (uy - 0.88f) * 1.1f
                            val w2 = kotlin.math.exp((-(dx * dx + dy * dy) * 5.2).toDouble()).toFloat()
                            lr = 1.0f * w2 * 0.78f; lg = 0.54f * w2 * 0.78f; lb = 0.12f * w2 * 0.78f
                        }
                        3 -> {
                            val diag = abs((ux * 0.85f + uy * 0.55f) - 0.36f)
                            val w3 = kotlin.math.exp((-diag * diag * 16.0).toDouble()).toFloat()
                            lr = 0.98f * w3 * 0.58f; lg = 0.34f * w3 * 0.58f; lb = 0.52f * w3 * 0.58f
                        }
                        4 -> {
                            val tx = (ux - 0.94f) * 4.2f; val ty = (uy - 0.48f) * 1.6f
                            val w4 = kotlin.math.exp((-(tx * tx + ty * ty)).toDouble()).toFloat()
                            lr = 1.0f * w4 * 0.75f; lg = 0.72f * w4 * 0.75f; lb = 0.22f * w4 * 0.75f
                        }
                        else -> {
                            val txL = ux * 4.2f; val txR = (1f - ux) * 4.2f
                            val wl = kotlin.math.exp((-txL * txL).toDouble()).toFloat()
                            val wr = kotlin.math.exp((-txR * txR).toDouble()).toFloat()
                            lr = 1.0f * wl * 0.62f + 1.0f * wr * 0.52f
                            lg = 0.30f * wl * 0.62f + 0.62f * wr * 0.52f
                            lb = 0.08f * wl * 0.62f + 0.16f * wr * 0.52f
                        }
                    }
                    val cr = (lr * amt).coerceIn(0f, 0.95f)
                    val cg = (lg * amt).coerceIn(0f, 0.95f)
                    val cb = (lb * amt).coerceIn(0f, 0.95f)
                    r = 1f - (1f - r.coerceIn(0f, 1f)) * (1f - cr)
                    g = 1f - (1f - g.coerceIn(0f, 1f)) * (1f - cg)
                    b = 1f - (1f - b.coerceIn(0f, 1f)) * (1f - cb)
                }

                // 11. Optional 90s VHS Retro Scanlines
                if (grade.vhsRetro > 0.01f) {
                    val scan = 0.5f + 0.5f * kotlin.math.sin((y.toFloat() * invH * 540.0)).toFloat()
                    val mul = 1f - 0.11f * grade.vhsRetro * scan
                    r *= mul; g *= mul; b *= mul
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

    /**
     * Scientific Zero-AI 3-Stage Optical ISP Denoiser (OFF by default — Pure Zero-AI remains default!):
     * 1) Decoupled YCbCr Edge-Aware Chroma Denoising (eliminates magenta/green sensor blotches with zero sharpness loss).
     * 2) ISO- & Shadow-Adaptive Separable Strided Bilateral Luminance Filter + 18% Organic Texture Retention
     *    (removes multi-pixel grain clumps on 12MP+ sensors without creating waxy/plastic skin).
     * 3) Coring (Thresholded) Micro-Clarity (crisps real optical edges while applying ZERO sharpening to flat areas).
     */
    fun applyCleanEnhance(source: Bitmap, iso: Int = 400): Bitmap {
        val w = source.width
        val h = source.height
        if (w < 8 || h < 8) return source
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        denoisePixelsInPlace(pixels, w, h, iso)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    internal fun denoisePixelsInPlace(pixels: IntArray, w: Int, h: Int, iso: Int = 400) {
        val count = w * h
        if (w < 8 || h < 8 || pixels.size < count) return

        val yPlane = FloatArray(count)
        val cbPlane = FloatArray(count)
        val crPlane = FloatArray(count)

        // Convert RGB -> BT.709 YCbCr so Chroma & Luminance noise are processed independently
        for (i in 0 until count) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF).toFloat()
            val g = ((c shr 8) and 0xFF).toFloat()
            val b = (c and 0xFF).toFloat()
            yPlane[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
            cbPlane[i] = -0.1146f * r - 0.3854f * g + 0.5000f * b + 128f
            crPlane[i] = 0.5000f * r - 0.4542f * g - 0.0458f * b + 128f
        }

        // Resolution-aware kernel scale (real 12MP phone grain clumps span 3-7px!)
        val hiRes = maxOf(w, h) >= 1600
        val chromaRadius = if (hiRes) 4 else 2
        val chromaEdgeStop = 38f

        // STAGE 1: Edge-Aware Separable Chroma Noise Reduction (Cb & Cr only — preserves 100% of edge sharpness!)
        val cbTmp = FloatArray(count)
        val crTmp = FloatArray(count)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                val cy = yPlane[idx]
                var sCb = cbPlane[idx]
                var sCr = crPlane[idx]
                var wSum = 1f
                for (d in -chromaRadius..chromaRadius) {
                    if (d == 0) continue
                    val nx = (x + d).coerceIn(0, w - 1)
                    val ni = row + nx
                    val dy = abs(yPlane[ni] - cy)
                    if (dy < chromaEdgeStop) {
                        val wt = 1f - (dy / chromaEdgeStop) * 0.65f
                        sCb += cbPlane[ni] * wt
                        sCr += crPlane[ni] * wt
                        wSum += wt
                    }
                }
                cbTmp[idx] = sCb / wSum
                crTmp[idx] = sCr / wSum
            }
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                val cy = yPlane[idx]
                var sCb = cbTmp[idx]
                var sCr = crTmp[idx]
                var wSum = 1f
                for (d in -chromaRadius..chromaRadius) {
                    if (d == 0) continue
                    val ny = (y + d).coerceIn(0, h - 1)
                    val ni = ny * w + x
                    val dy = abs(yPlane[ni] - cy)
                    if (dy < chromaEdgeStop) {
                        val wt = 1f - (dy / chromaEdgeStop) * 0.65f
                        sCb += cbTmp[ni] * wt
                        sCr += crTmp[ni] * wt
                        wSum += wt
                    }
                }
                cbPlane[idx] = sCb / wSum
                crPlane[idx] = sCr / wSum
            }
        }

        // STAGE 2: ISO- & Shadow-Adaptive Separable Bilateral Luminance Filter (Y only)
        // Photon shot noise scales with ISO and is strongest in low-luminance shadows.
        val isoFactor = ((iso.coerceIn(100, 3200) - 100f) / 1500f).coerceIn(0f, 1.4f)
        val sigmaMid = 15f + isoFactor * 11f        // 15..30 (crisp midtones/skin)
        val sigmaShadow = sigmaMid * 1.42f          // gentler shadow cleanup where SNR is lowest
        val lutMid = FloatArray(256) { d -> kotlin.math.exp(-(d * d) / (2f * sigmaMid * sigmaMid)) }
        val lutShadow = FloatArray(256) { d -> kotlin.math.exp(-(d * d) / (2f * sigmaShadow * sigmaShadow)) }

        val step = if (hiRes) 2 else 1
        val offsets = intArrayOf(-2 * step, -step, 0, step, 2 * step)
        val spatialW = floatArrayOf(0.55f, 0.85f, 1.0f, 0.85f, 0.55f)

        val yTmp = FloatArray(count)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                val cy = yPlane[idx]
                val lut = if (cy < 92f) lutShadow else lutMid
                var acc = 0f
                var wSum = 0f
                for (k in 0 until 5) {
                    val nx = (x + offsets[k]).coerceIn(0, w - 1)
                    val nyVal = yPlane[row + nx]
                    val diff = abs(nyVal - cy).toInt().coerceIn(0, 255)
                    val wt = spatialW[k] * lut[diff]
                    acc += nyVal * wt
                    wSum += wt
                }
                yTmp[idx] = acc / wSum.coerceAtLeast(1e-4f)
            }
        }

        val yClean = FloatArray(count)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                val cy = yTmp[idx]
                val lut = if (cy < 92f) lutShadow else lutMid
                var acc = 0f
                var wSum = 0f
                for (k in 0 until 5) {
                    val ny = (y + offsets[k]).coerceIn(0, h - 1)
                    val nyVal = yTmp[ny * w + x]
                    val diff = abs(nyVal - cy).toInt().coerceIn(0, 255)
                    val wt = spatialW[k] * lut[diff]
                    acc += nyVal * wt
                    wSum += wt
                }
                val denoisedY = acc / wSum.coerceAtLeast(1e-4f)
                // Anti-Plastic Organic Retention: keep 18% of original luminance micro-texture so skin stays natural
                yClean[idx] = denoisedY * 0.82f + yPlane[idx] * 0.18f
            }
        }

        // STAGE 3: Coring (Thresholded) Optical Micro-Clarity — ONLY crisps real edges, NEVER sharpens flat grain!
        val coringDeadZone = 6.5f
        val clarityStrength = 0.20f
        for (y in 0 until h) {
            val row = y * w
            val rowUp = (y - 1).coerceAtLeast(0) * w
            val rowDn = (y + 1).coerceAtMost(h - 1) * w
            for (x in 0 until w) {
                val idx = row + x
                val xL = (x - 1).coerceAtLeast(0)
                val xR = (x + 1).coerceAtMost(w - 1)
                val yc = yClean[idx]
                val localAvg = (yc * 2f + yClean[rowUp + x] + yClean[rowDn + x] + yClean[row + xL] + yClean[row + xR]) / 6f
                val diff = yc - localAvg
                val edgeBoost = when {
                    diff > coringDeadZone -> ((diff - coringDeadZone) * clarityStrength).coerceAtMost(14f)
                    diff < -coringDeadZone -> ((diff + coringDeadZone) * clarityStrength).coerceAtLeast(-14f)
                    else -> 0f // flat noisy region: ZERO sharpening!
                }

                val yf = (yc + edgeBoost).coerceIn(0f, 255f)
                val cb = cbPlane[idx] - 128f
                val cr = crPlane[idx] - 128f

                // BT.709 YCbCr -> RGB
                val r = (yf + 1.5748f * cr).toInt().coerceIn(0, 255)
                val g = (yf - 0.1873f * cb - 0.4681f * cr).toInt().coerceIn(0, 255)
                val b = (yf + 1.8556f * cb).toInt().coerceIn(0, 255)
                pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
