package com.truelens.camera.grading

import android.graphics.Bitmap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure Optical Multi-Exposure HDR & Filmic Highlight Engine (Zero-AI).
 *
 * Implements two landmark computational photography algorithms:
 *
 * 1) **Mertens, Kautz & Van Reeth (2007) Exposure Fusion via Multi-Resolution Laplacian Pyramids**:
 *    - Takes a rapid 3-frame exposure bracket (`-1.5 EV`, `0 EV`, `+1.0 EV`) captured from the sensor.
 *    - Aligns the under/over-exposed frames to the `0 EV` reference using exposure-normalized
 *      zero-mean gradient alignment (immune to brightness differences!).
 *    - Computes per-pixel optical quality weights = Well-Exposedness × Local Contrast × Saturation
 *      × Motion Ghost-Rejection (preventing double-image ghosting on moving subjects!).
 *    - Blends across a 4-level Gaussian/Laplacian pyramid so bright skies, clouds, and dark shadows
 *      merge seamlessly with ZERO fake HDR haloing.
 *
 * 2) **AgX-Inspired Hue-Preserving Filmic Highlight Roll-Off**:
 *    - Compresses extreme highlights in linear light while locking the original perceptual hue vector
 *      and smoothly desaturating toward pure white — preventing ugly yellow-shifted sunsets and
 *      neon-cyan sky clipping.
 */
object OpticalHdrEngine {

    private val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    private val pool = Executors.newFixedThreadPool(workerCount) { r ->
        Thread(r, "TrueLensHdr").apply { isDaemon = true }
    }

    private fun parallelForRows(h: Int, block: (startY: Int, endY: Int) -> Unit) {
        if (h < 128 || workerCount <= 1) {
            block(0, h)
            return
        }
        val chunks = workerCount.coerceAtMost(h / 32).coerceAtLeast(1)
        val step = (h + chunks - 1) / chunks
        val tasks = ArrayList<Callable<Unit>>(chunks)
        var y = 0
        while (y < h) {
            val sy = y
            val ey = (y + step).coerceAtMost(h)
            tasks.add(Callable { block(sy, ey) })
            y = ey
        }
        pool.invokeAll(tasks).forEach { it.get() }
    }

    /**
     * Memory-safe lazy HDR bracket fusion: decodes the 3 exposure frames, aligns and fuses via
     * 4-level Laplacian pyramids, and finishes with AgX hue-preserving highlight roll-off.
     */
    fun fuseLazyBracket(
        frameCount: Int,
        refIndex: Int = 1,
        decodeFullFrame: (Int) -> Bitmap?
    ): Bitmap? {
        if (frameCount <= 0) return null
        if (frameCount == 1) {
            val single = decodeFullFrame(0) ?: return null
            val w = single.width
            val h = single.height
            val px = IntArray(w * h)
            single.getPixels(px, 0, w, 0, 0, w, h)
            single.recycle()
            applyAgxHighlightRolloffInPlace(px, w, h, strength = 0.55f)
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }

        val safeRef = refIndex.coerceIn(0, frameCount - 1)
        val refBmp = decodeFullFrame(safeRef) ?: decodeFullFrame(0) ?: return null
        val w = refBmp.width
        val h = refBmp.height
        val count = w * h
        val framesPx = ArrayList<IntArray>(frameCount)

        for (i in 0 until frameCount) {
            if (i == safeRef) {
                val arr = IntArray(count)
                refBmp.getPixels(arr, 0, w, 0, 0, w, h)
                framesPx.add(arr)
            } else {
                val bmp = decodeFullFrame(i) ?: continue
                val sized = if (bmp.width != w || bmp.height != h) {
                    Bitmap.createScaledBitmap(bmp, w, h, true).also { bmp.recycle() }
                } else bmp
                val arr = IntArray(count)
                sized.getPixels(arr, 0, w, 0, 0, w, h)
                sized.recycle()
                framesPx.add(arr)
            }
        }
        refBmp.recycle()

        if (framesPx.size == 1) {
            applyAgxHighlightRolloffInPlace(framesPx[0], w, h, strength = 0.55f)
            return Bitmap.createBitmap(framesPx[0], w, h, Bitmap.Config.ARGB_8888)
        }

        val actualRefIdx = safeRef.coerceIn(0, framesPx.lastIndex)
        val fused = fuseAlignedBracketPixels(w, h, framesPx, refIndex = actualRefIdx)
        return Bitmap.createBitmap(fused, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Pure JVM-testable Mertens Exposure Fusion via 4-level Laplacian Pyramids + Ghost Rejection
     * + AgX Hue-Preserving Filmic Highlight Roll-Off.
     */
    internal fun fuseAlignedBracketPixels(
        w: Int,
        h: Int,
        frames: List<IntArray>,
        refIndex: Int = frames.size / 2
    ): IntArray {
        val count = w * h
        if (frames.isEmpty()) return IntArray(0)
        if (frames.size == 1 || w < 16 || h < 16) return frames[0].copyOf()

        val n = frames.size
        val refIdx = refIndex.coerceIn(0, n - 1)
        val refPx = frames[refIdx]
        val refNormY = buildExposureInvariantEdgeMap(refPx, w, h)
        val refMeanY = computeMeanLuma(refPx, w, h).coerceAtLeast(8f)

        // 1. Align every non-reference exposure frame to the 0 EV reference (exposure-invariant!)
        val alignedFrames = Array(n) { i ->
            if (i == refIdx) {
                refPx
            } else {
                val curNormY = buildExposureInvariantEdgeMap(frames[i], w, h)
                val (dx, dy) = estimateBracketShift(refNormY, curNormY, w, h)
                if (dx == 0 && dy == 0) frames[i]
                else shiftFrameClamped(frames[i], w, h, dx, dy)
            }
        }

        // 2. Compute Mertens optical quality weight maps (Well-Exposedness × Contrast × Saturation × GhostGuard)
        val weights = Array(n) { FloatArray(count) }
        val sumW = FloatArray(count)

        for (k in 0 until n) {
            val px = alignedFrames[k]
            val wArr = weights[k]
            val meanY = computeMeanLuma(px, w, h).coerceAtLeast(8f)
            val expRatio = (refMeanY / meanY).coerceIn(0.25f, 4.0f)

            parallelForRows(h) { sy, ey ->
                for (y in sy until ey) {
                    val row = y * w
                    val rowU = (y - 1).coerceAtLeast(0) * w
                    val rowD = (y + 1).coerceAtMost(h - 1) * w
                    for (x in 0 until w) {
                        val idx = row + x
                        val p = px[idx]
                        val r = ((p shr 16) and 0xFF) / 255f
                        val g = ((p shr 8) and 0xFF) / 255f
                        val b = (p and 0xFF) / 255f
                        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b

                        // Well-exposedness: Gaussian centered at 0.5 (sigma = 0.22)
                        val dExp = luma - 0.5f
                        val wellExp = exp(-(dExp * dExp) / (2f * 0.22f * 0.22f))

                        // Saturation: RGB standard deviation
                        val meanRgb = (r + g + b) / 3f
                        val sat = sqrt(
                            ((r - meanRgb) * (r - meanRgb) +
                                (g - meanRgb) * (g - meanRgb) +
                                (b - meanRgb) * (b - meanRgb)) / 3f
                        ) + 0.08f

                        // Local contrast: fast 4-neighbor Laplacian on luma
                        val xL = (x - 1).coerceAtLeast(0)
                        val xR = (x + 1).coerceAtMost(w - 1)
                        val lU = luma01(px[rowU + x])
                        val lD = luma01(px[rowD + x])
                        val lL = luma01(px[row + xL])
                        val lR = luma01(px[row + xR])
                        val contrast = abs(4f * luma - lU - lD - lL - lR) + 0.05f

                        // Ghost rejection against 0 EV reference (only where ref isn't clipped/crushed!)
                        var ghostGuard = 1f
                        if (k != refIdx) {
                            val rLuma = luma01(refPx[idx])
                            if (rLuma in 0.12f..0.88f) {
                                val expectedLuma = (luma * expRatio).coerceIn(0f, 1f)
                                val motionDiff = abs(expectedLuma - rLuma)
                                if (motionDiff > 0.22f) {
                                    val t = ((motionDiff - 0.22f) / 0.28f).coerceIn(0f, 1f)
                                    ghostGuard = 1f - t * t * (3f - 2f * t)
                                }
                            }
                        }

                        val wt = (wellExp * contrast * sat * ghostGuard).coerceAtLeast(1e-5f)
                        wArr[idx] = wt
                    }
                }
            }
        }

        // Normalize weights across frames
        parallelForRows(h) { sy, ey ->
            for (y in sy until ey) {
                val row = y * w
                for (x in 0 until w) {
                    val idx = row + x
                    var s = 0f
                    for (k in 0 until n) s += weights[k][idx]
                    val inv = 1f / s.coerceAtLeast(1e-5f)
                    for (k in 0 until n) weights[k][idx] *= inv
                    sumW[idx] = s
                }
            }
        }

        // 3. Multi-Resolution 4-Level Laplacian Pyramid Fusion (Mertens et al. 2007)
        val levels = when {
            minOf(w, h) >= 256 -> 4
            minOf(w, h) >= 64 -> 3
            else -> 2
        }

        // Allocate output Laplacian pyramid accumulators for R, G, B at each scale
        val levelW = IntArray(levels)
        val levelH = IntArray(levels)
        levelW[0] = w
        levelH[0] = h
        for (l in 1 until levels) {
            levelW[l] = (levelW[l - 1] + 1) / 2
            levelH[l] = (levelH[l - 1] + 1) / 2
        }

        val fusedPyrR = Array(levels) { l -> FloatArray(levelW[l] * levelH[l]) }
        val fusedPyrG = Array(levels) { l -> FloatArray(levelW[l] * levelH[l]) }
        val fusedPyrB = Array(levels) { l -> FloatArray(levelW[l] * levelH[l]) }

        // Process one exposure frame at a time to keep memory footprint ultra-low!
        for (k in 0 until n) {
            val px = alignedFrames[k]
            var curR = FloatArray(count)
            var curG = FloatArray(count)
            var curB = FloatArray(count)
            var curW = weights[k]

            parallelForRows(h) { sy, ey ->
                for (y in sy until ey) {
                    val row = y * w
                    for (x in 0 until w) {
                        val idx = row + x
                        val p = px[idx]
                        curR[idx] = ((p shr 16) and 0xFF) / 255f
                        curG[idx] = ((p shr 8) and 0xFF) / 255f
                        curB[idx] = (p and 0xFF) / 255f
                    }
                }
            }

            for (l in 0 until levels) {
                val cw = levelW[l]
                val ch = levelH[l]
                val outR = fusedPyrR[l]
                val outG = fusedPyrG[l]
                val outB = fusedPyrB[l]

                if (l == levels - 1) {
                    // Coarsest residual level: accumulate directly
                    for (i in 0 until cw * ch) {
                        val wt = curW[i]
                        outR[i] += curR[i] * wt
                        outG[i] += curG[i] * wt
                        outB[i] += curB[i] * wt
                    }
                } else {
                    val nw = levelW[l + 1]
                    val nh = levelH[l + 1]
                    val nextR = downsampleGaussian2x(curR, cw, ch, nw, nh)
                    val nextG = downsampleGaussian2x(curG, cw, ch, nw, nh)
                    val nextB = downsampleGaussian2x(curB, cw, ch, nw, nh)
                    val nextW = downsampleGaussian2x(curW, cw, ch, nw, nh)

                    val upR = upsampleBilinear(nextR, nw, nh, cw, ch)
                    val upG = upsampleBilinear(nextG, nw, nh, cw, ch)
                    val upB = upsampleBilinear(nextB, nw, nh, cw, ch)

                    // Laplacian detail = cur - upsample(downsample(cur))
                    parallelForRows(ch) { sy, ey ->
                        for (y in sy until ey) {
                            val row = y * cw
                            for (x in 0 until cw) {
                                val idx = row + x
                                val wt = curW[idx]
                                outR[idx] += (curR[idx] - upR[idx]) * wt
                                outG[idx] += (curG[idx] - upG[idx]) * wt
                                outB[idx] += (curB[idx] - upB[idx]) * wt
                            }
                        }
                    }
                    curR = nextR
                    curG = nextG
                    curB = nextB
                    curW = nextW
                }
            }
        }

        // 4. Collapse fused Laplacian pyramid from coarsest -> finest
        var recR = fusedPyrR[levels - 1]
        var recG = fusedPyrG[levels - 1]
        var recB = fusedPyrB[levels - 1]
        for (l in levels - 2 downTo 0) {
            val cw = levelW[l]
            val ch = levelH[l]
            val nw = levelW[l + 1]
            val nh = levelH[l + 1]
            val upR = upsampleBilinear(recR, nw, nh, cw, ch)
            val upG = upsampleBilinear(recG, nw, nh, cw, ch)
            val upB = upsampleBilinear(recB, nw, nh, cw, ch)
            val lapR = fusedPyrR[l]
            val lapG = fusedPyrG[l]
            val lapB = fusedPyrB[l]
            parallelForRows(ch) { sy, ey ->
                for (y in sy until ey) {
                    val row = y * cw
                    for (x in 0 until cw) {
                        val idx = row + x
                        upR[idx] += lapR[idx]
                        upG[idx] += lapG[idx]
                        upB[idx] += lapB[idx]
                    }
                }
            }
            recR = upR
            recG = upG
            recB = upB
        }

        val outPixels = IntArray(count)
        parallelForRows(h) { sy, ey ->
            for (y in sy until ey) {
                val row = y * w
                for (x in 0 until w) {
                    val idx = row + x
                    val r = (recR[idx].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    val g = (recG[idx].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    val b = (recB[idx].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    outPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        // 5. Finish with AgX Hue-Preserving Filmic Highlight Roll-Off (natural skies & sunsets!)
        applyAgxHighlightRolloffInPlace(outPixels, w, h, strength = 0.60f)
        return outPixels
    }

    /**
     * AgX-Inspired Hue-Preserving Filmic Highlight Roll-Off.
     *
     * Operates in linear light (~gamma 2.2), applying a soft highlight shoulder and gentle
     * filmic desaturation toward pure white ONLY on high-luminance regions while strictly
     * preserving the original hue vector (preventing yellow sunsets & neon-cyan sky clipping).
     */
    internal fun applyAgxHighlightRolloffInPlace(
        pixels: IntArray,
        w: Int,
        h: Int,
        strength: Float = 0.55f
    ) {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0.01f || pixels.size < w * h) return

        // Precompute fast sRGB <-> Linear LUTs (256 entries -> zero pow() per pixel!)
        val srgbToLin = FloatArray(256) { i -> (i / 255f).pow(2.2f) }
        val linToSrgbLut = IntArray(4096) { i ->
            val v = (i / 4095f).pow(1f / 2.2f)
            (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        }

        parallelForRows(h) { sy, ey ->
            for (y in sy until ey) {
                val row = y * w
                for (x in 0 until w) {
                    val idx = row + x
                    val c = pixels[idx]
                    val rLin = srgbToLin[(c shr 16) and 0xFF]
                    val gLin = srgbToLin[(c shr 8) and 0xFF]
                    val bLin = srgbToLin[c and 0xFF]

                    val luma = 0.2126f * rLin + 0.7152f * gLin + 0.0722f * bLin
                    if (luma <= 0.42f) continue // Leave shadows & midtones 100% untouched!

                    // Smooth AgX shoulder compression above linear 0.42
                    val excess = (luma - 0.42f) / 0.58f // 0..1
                    val shoulderLuma = 0.42f + 0.58f * (excess / (1f + 0.28f * s * excess)) * (1f + 0.28f * s)
                    val scale = (shoulderLuma / luma.coerceAtLeast(1e-4f)).coerceIn(0.6f, 1.15f)

                    // Scale RGB uniformly by luminance ratio (preserves EXACT hue angle!)
                    var nr = rLin * scale
                    var ng = gLin * scale
                    var nb = bLin * scale

                    // Gentle AgX highlight desaturation path-to-white near clipping (eliminates harsh neon clipping)
                    val peak = maxOf(nr, maxOf(ng, nb))
                    if (peak > 0.78f) {
                        val desatT = (((peak - 0.78f) / 0.28f).coerceIn(0f, 1f)) * 0.38f * s
                        val nl = (0.2126f * nr + 0.7152f * ng + 0.0722f * nb).coerceAtMost(1f)
                        nr = nr * (1f - desatT) + nl * desatT
                        ng = ng * (1f - desatT) + nl * desatT
                        nb = nb * (1f - desatT) + nl * desatT
                    }

                    val ri = linToSrgbLut[(nr.coerceIn(0f, 1f) * 4095f + 0.5f).toInt()]
                    val gi = linToSrgbLut[(ng.coerceIn(0f, 1f) * 4095f + 0.5f).toInt()]
                    val bi = linToSrgbLut[(nb.coerceIn(0f, 1f) * 4095f + 0.5f).toInt()]
                    pixels[idx] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
            }
        }
    }

    private fun luma01(p: Int): Float =
        (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f

    private fun computeMeanLuma(px: IntArray, w: Int, h: Int): Float {
        var sum = 0.0
        var n = 0
        val step = (minOf(w, h) / 48).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            val row = y * w
            var x = 0
            while (x < w) {
                val p = px[row + x]
                sum += 0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)
                n++
                x += step
            }
            y += step
        }
        return (sum / n.coerceAtLeast(1)).toFloat()
    }

    /** Zero-mean normalized quarter-scale edge map for exposure-invariant bracket alignment. */
    private fun buildExposureInvariantEdgeMap(px: IntArray, w: Int, h: Int): FloatArray {
        val scale = if (maxOf(w, h) >= 640) 4 else 1
        val dw = (w / scale).coerceAtLeast(8)
        val dh = (h / scale).coerceAtLeast(8)
        val small = FloatArray(dw * dh)
        var mean = 0.0
        for (y in 0 until dh) {
            val fy = (y * scale).coerceAtMost(h - 1)
            val fRow = fy * w
            val oRow = y * dw
            for (x in 0 until dw) {
                val fx = (x * scale).coerceAtMost(w - 1)
                val v = luma01(px[fRow + fx])
                small[oRow + x] = v
                mean += v
            }
        }
        val m = (mean / (dw * dh).coerceAtLeast(1)).toFloat()
        var varSum = 0.0
        for (i in small.indices) {
            val d = small[i] - m
            small[i] = d
            varSum += d * d
        }
        val invStd = 1f / sqrt((varSum / small.size.coerceAtLeast(1)).toFloat()).coerceAtLeast(0.02f)
        for (i in small.indices) small[i] *= invStd
        return small
    }

    private fun estimateBracketShift(refMap: FloatArray, curMap: FloatArray, fullW: Int, fullH: Int): Pair<Int, Int> {
        val scale = if (maxOf(fullW, fullH) >= 640) 4 else 1
        val dw = (fullW / scale).coerceAtLeast(8)
        val dh = (fullH / scale).coerceAtLeast(8)
        val rad = if (scale == 4) 6 else 4
        val x0 = (dw / 5).coerceAtLeast(rad + 1)
        val y0 = (dh / 5).coerceAtLeast(rad + 1)
        val x1 = ((dw * 4) / 5).coerceAtMost(dw - rad - 1)
        val y1 = ((dh * 4) / 5).coerceAtMost(dh - rad - 1)
        if (x1 <= x0 || y1 <= y0) return 0 to 0

        val stride = (minOf(x1 - x0, y1 - y0) / 40).coerceAtLeast(1)
        var bestDx = 0
        var bestDy = 0
        var bestSad = Float.MAX_VALUE
        for (dy in -rad..rad) {
            for (dx in -rad..rad) {
                var sad = 0f
                var y = y0
                while (y < y1) {
                    val rRow = y * dw
                    val cRow = (y + dy) * dw
                    var x = x0
                    while (x < x1) {
                        sad += abs(curMap[cRow + x + dx] - refMap[rRow + x])
                        x += stride
                    }
                    y += stride
                }
                if (dx == 0 && dy == 0) sad *= 0.985f
                if (sad < bestSad) {
                    bestSad = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return (bestDx * scale) to (bestDy * scale)
    }

    private fun shiftFrameClamped(px: IntArray, w: Int, h: Int, dx: Int, dy: Int): IntArray {
        val out = IntArray(w * h)
        parallelForRows(h) { sy, ey ->
            for (y in sy until ey) {
                val srcY = (y + dy).coerceIn(0, h - 1) * w
                val dstRow = y * w
                for (x in 0 until w) {
                    val srcX = (x + dx).coerceIn(0, w - 1)
                    out[dstRow + x] = px[srcY + srcX]
                }
            }
        }
        return out
    }

    private fun downsampleGaussian2x(src: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        val out = FloatArray(nw * nh)
        for (ny in 0 until nh) {
            val y0 = (ny * 2).coerceAtMost(h - 1)
            val yU = (y0 - 1).coerceAtLeast(0) * w
            val yC = y0 * w
            val yD = (y0 + 1).coerceAtMost(h - 1) * w
            val oRow = ny * nw
            for (nx in 0 until nw) {
                val x0 = (nx * 2).coerceAtMost(w - 1)
                val xL = (x0 - 1).coerceAtLeast(0)
                val xR = (x0 + 1).coerceAtMost(w - 1)
                out[oRow + nx] = (
                    src[yU + xL] + 2f * src[yU + x0] + src[yU + xR] +
                        2f * src[yC + xL] + 4f * src[yC + x0] + 2f * src[yC + xR] +
                        src[yD + xL] + 2f * src[yD + x0] + src[yD + xR]
                    ) * 0.0625f
            }
        }
        return out
    }

    private fun upsampleBilinear(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        val scaleX = if (dw > 1) (sw - 1).toFloat() / (dw - 1).toFloat() else 0f
        val scaleY = if (dh > 1) (sh - 1).toFloat() / (dh - 1).toFloat() else 0f
        parallelForRows(dh) { sy, ey ->
            for (y in sy until ey) {
                val fy = y * scaleY
                val y0 = fy.toInt().coerceIn(0, sh - 1)
                val y1 = (y0 + 1).coerceAtMost(sh - 1)
                val wy = fy - y0
                val wy0 = 1f - wy
                val r0 = y0 * sw
                val r1 = y1 * sw
                val oRow = y * dw
                for (x in 0 until dw) {
                    val fx = x * scaleX
                    val x0 = fx.toInt().coerceIn(0, sw - 1)
                    val x1 = (x0 + 1).coerceAtMost(sw - 1)
                    val wx = fx - x0
                    val wx0 = 1f - wx
                    out[oRow + x] = (src[r0 + x0] * wx0 + src[r0 + x1] * wx) * wy0 +
                        (src[r1 + x0] * wx0 + src[r1 + x1] * wx) * wy
                }
            }
        }
        return out
    }
}
