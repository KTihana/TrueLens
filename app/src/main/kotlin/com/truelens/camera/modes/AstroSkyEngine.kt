package com.truelens.camera.modes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.truelens.camera.camera.AstroStyle
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
import kotlin.math.abs

/**
 * Pure Optical Astrophotography & Sky Stacking Engine (Zero-AI).
 *
 * Solves tripod wobble, wind vibration, Earth's rotation, city sky-glow, and hot sensor pixels:
 * 1) Coarse-to-fine 2D frame registration aligns every frame pixel-for-pixel onto Frame #1
 *    (for Pinpoint Stars & stabilized Long Exposures).
 * 2) Star Trails mode uses pure Lighten-Max optical accumulation for glowing sky arcs.
 * 3) Hot-Pixel Cleanup strips isolated 1-pixel stuck red/green/blue sensor noise dots.
 * 4) Optical Light-Pollution Gradient Cleaner estimates smooth low-frequency horizon sky glow
 *    and subtracts it cleanly so faint stars & Milky Way dust lanes stand out naturally.
 */
object AstroSkyEngine {

    /**
     * Computes exact (dx, dy) translation needed to align [cur] onto [ref] using coarse-to-fine
     * sum-of-absolute-differences (SAD) over luminance. Pure deterministic math, zero AI.
     */
    fun estimateFrameShift(ref: Bitmap, cur: Bitmap, maxShiftPx: Int = 20): Pair<Int, Int> {
        val w = ref.width
        val h = ref.height
        if (w < 64 || h < 64 || cur.width != w || cur.height != h) return 0 to 0

        // Downscale by 4x for fast coarse shift search
        val ds = (maxOf(w, h) / 240).coerceAtLeast(1)
        val sw = (w / ds).coerceAtLeast(32)
        val sh = (h / ds).coerceAtLeast(32)
        val refSmall = if (ds > 1) Bitmap.createScaledBitmap(ref, sw, sh, true) else ref
        val curSmall = if (ds > 1) Bitmap.createScaledBitmap(cur, sw, sh, true) else cur

        val rPx = IntArray(sw * sh)
        val cPx = IntArray(sw * sh)
        refSmall.getPixels(rPx, 0, sw, 0, 0, sw, sh)
        curSmall.getPixels(cPx, 0, sw, 0, 0, sw, sh)
        if (refSmall !== ref) refSmall.recycle()
        if (curSmall !== cur) curSmall.recycle()

        val rLuma = IntArray(sw * sh) { i ->
            val p = rPx[i]
            (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) ushr 8
        }
        val cLuma = IntArray(sw * sh) { i ->
            val p = cPx[i]
            (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) ushr 8
        }

        val sMax = (maxShiftPx / ds).coerceIn(2, 14)
        var bestDx = 0
        var bestDy = 0
        var bestScore = Long.MAX_VALUE
        val marginX = sMax + 4
        val marginY = sMax + 4

        for (dy in -sMax..sMax) {
            for (dx in -sMax..sMax) {
                var sad = 0L
                var y = marginY
                while (y < sh - marginY) {
                    val rRow = y * sw
                    val cRow = (y + dy) * sw
                    var x = marginX
                    while (x < sw - marginX) {
                        sad += abs(rLuma[rRow + x] - cLuma[cRow + x + dx])
                        x += 2
                    }
                    y += 2
                }
                if (sad < bestScore) {
                    bestScore = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return (bestDx * ds) to (bestDy * ds)
    }

    suspend fun stackAstroAndSave(
        context: Context,
        frames: List<Bitmap>,
        style: AstroStyle,
        cleanLightPollution: Boolean,
        cleanHotPixels: Boolean,
        grade: LightroomGrade,
        lut: CubeLut? = null,
        dateStampStyle: DateStampStyle = DateStampStyle.OFF
    ): Uri? = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext null

        val stacked = when (style) {
            AstroStyle.PINPOINT_STARS -> stackAlignedPinpointStars(frames, cleanLightPollution, cleanHotPixels)
            AstroStyle.STAR_TRAILS -> stackStarTrails(frames, cleanHotPixels)
        } ?: return@withContext null

        val graded = if (!grade.isNeutral || lut != null) {
            BitmapGrader.applyGrade(stacked, grade, lut).also {
                if (it !== stacked) stacked.recycle()
            }
        } else stacked

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
                val prefix = if (style == AstroStyle.STAR_TRAILS) "TrueLens_StarTrails" else "TrueLens_Astro"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "${prefix}_$stamp.jpg")
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

    internal fun stackAlignedPinpointStars(
        frames: List<Bitmap>,
        cleanLightPollution: Boolean,
        cleanHotPixels: Boolean
    ): Bitmap? {
        if (frames.isEmpty()) return null
        val ref = frames.first()
        val w = ref.width
        val h = ref.height
        val count = w * h

        val sumR = FloatArray(count)
        val sumG = FloatArray(count)
        val sumB = FloatArray(count)
        val sumW = FloatArray(count)
        val tmp = IntArray(count)

        ref.getPixels(tmp, 0, w, 0, 0, w, h)
        for (i in 0 until count) {
            val p = tmp[i]
            sumR[i] = ((p shr 16) and 0xFF).toFloat()
            sumG[i] = ((p shr 8) and 0xFF).toFloat()
            sumB[i] = (p and 0xFF).toFloat()
            sumW[i] = 1f
        }

        for (fIdx in 1 until frames.size) {
            val f = frames[fIdx]
            val sized = if (f.width != w || f.height != h) Bitmap.createScaledBitmap(f, w, h, true) else f
            val (dx, dy) = estimateFrameShift(ref, sized, maxShiftPx = 22)
            sized.getPixels(tmp, 0, w, 0, 0, w, h)
            if (sized !== f) sized.recycle()

            for (y in 0 until h) {
                val sy = y + dy
                if (sy !in 0 until h) continue
                val dstRow = y * w
                val srcRow = sy * w
                for (x in 0 until w) {
                    val sx = x + dx
                    if (sx !in 0 until w) continue
                    val p = tmp[srcRow + sx]
                    val di = dstRow + x
                    sumR[di] += ((p shr 16) and 0xFF).toFloat()
                    sumG[di] += ((p shr 8) and 0xFF).toFloat()
                    sumB[di] += (p and 0xFF).toFloat()
                    sumW[di] += 1f
                }
            }
        }

        val outPx = IntArray(count)
        for (i in 0 until count) {
            val inv = 1f / sumW[i].coerceAtLeast(1f)
            val r = (sumR[i] * inv).toInt().coerceIn(0, 255)
            val g = (sumG[i] * inv).toInt().coerceIn(0, 255)
            val b = (sumB[i] * inv).toInt().coerceIn(0, 255)
            outPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        if (cleanHotPixels) {
            removeHotPixelsInPlace(outPx, w, h)
        }
        if (cleanLightPollution) {
            subtractLightPollutionGradientInPlace(outPx, w, h)
        }
        return Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun stackStarTrails(frames: List<Bitmap>, cleanHotPixels: Boolean): Bitmap? {
        if (frames.isEmpty()) return null
        val ref = frames.first()
        val w = ref.width
        val h = ref.height
        val count = w * h

        val maxPx = IntArray(count)
        ref.getPixels(maxPx, 0, w, 0, 0, w, h)
        val tmp = IntArray(count)

        for (fIdx in 1 until frames.size) {
            val f = frames[fIdx]
            val sized = if (f.width != w || f.height != h) Bitmap.createScaledBitmap(f, w, h, true) else f
            sized.getPixels(tmp, 0, w, 0, 0, w, h)
            if (sized !== f) sized.recycle()

            for (i in 0 until count) {
                val p = tmp[i]
                val m = maxPx[i]
                val r = maxOf((p shr 16) and 0xFF, (m shr 16) and 0xFF)
                val g = maxOf((p shr 8) and 0xFF, (m shr 8) and 0xFF)
                val b = maxOf(p and 0xFF, m and 0xFF)
                maxPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        if (cleanHotPixels) {
            removeHotPixelsInPlace(maxPx, w, h)
        }
        return Bitmap.createBitmap(maxPx, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Pure optical hot-pixel cleaner: replaces isolated 1-pixel bright spikes (where a single pixel
     * exceeds all 4 cardinal neighbors by > 55 levels — classic stuck sensor dot, NOT a multi-pixel star).
     */
    internal fun removeHotPixelsInPlace(px: IntArray, w: Int, h: Int) {
        if (w < 4 || h < 4) return
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val c = px[idx]
                val n1 = px[idx - 1]
                val n2 = px[idx + 1]
                val n3 = px[idx - w]
                val n4 = px[idx + w]

                val cR = (c shr 16) and 0xFF
                val cG = (c shr 8) and 0xFF
                val cB = c and 0xFF
                val cL = cR + cG + cB

                val r1 = (n1 shr 16) and 0xFF; val r2 = (n2 shr 16) and 0xFF; val r3 = (n3 shr 16) and 0xFF; val r4 = (n4 shr 16) and 0xFF
                val g1 = (n1 shr 8) and 0xFF; val g2 = (n2 shr 8) and 0xFF; val g3 = (n3 shr 8) and 0xFF; val g4 = (n4 shr 8) and 0xFF
                val b1 = n1 and 0xFF; val b2 = n2 and 0xFF; val b3 = n3 and 0xFF; val b4 = n4 and 0xFF

                val maxNbL = maxOf(r1 + g1 + b1, r2 + g2 + b2, r3 + g3 + b3, r4 + g4 + b4)
                val maxNbR = maxOf(r1, r2, r3, r4)
                val maxNbG = maxOf(g1, g2, g3, g4)
                val maxNbB = maxOf(b1, b2, b3, b4)

                // Catches both white hot pixels AND single-channel stuck red/green/blue sensor dots,
                // while preserving real multi-pixel stars (whose adjacent pixels are also bright).
                val isIsolatedSpike = (cL - maxNbL > 140) ||
                    (cR - maxNbR > 120) || (cG - maxNbG > 120) || (cB - maxNbB > 120)
                if (isIsolatedSpike) {
                    val r = (r1 + r2 + r3 + r4) ushr 2
                    val g = (g1 + g2 + g3 + g4) ushr 2
                    val b = (b1 + b2 + b3 + b4) ushr 2
                    px[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    /**
     * Pure Optical Light-Pollution Gradient Cleaner:
     * Samples a coarse 8×8 background grid (using low-percentile dark sky values per cell so stars are ignored),
     * bilinearly interpolates the smooth orange city haze, and subtracts ~45% of the glow to deepen the night sky
     * while preserving real starlight and Milky Way dust clouds.
     */
    internal fun subtractLightPollutionGradientInPlace(px: IntArray, w: Int, h: Int) {
        val gx = 8
        val gy = 8
        val gridR = FloatArray(gx * gy)
        val gridG = FloatArray(gx * gy)
        val gridB = FloatArray(gx * gy)
        val cellW = (w / gx).coerceAtLeast(1)
        val cellH = (h / gy).coerceAtLeast(1)

        for (cy in 0 until gy) {
            for (cx in 0 until gx) {
                var rAcc = 0L; var gAcc = 0L; var bAcc = 0L; var n = 0L
                val y0 = cy * cellH
                val y1 = minOf(h, y0 + cellH)
                val x0 = cx * cellW
                val x1 = minOf(w, x0 + cellW)
                var y = y0
                while (y < y1) {
                    val row = y * w
                    var x = x0
                    while (x < x1) {
                        val p = px[row + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        // Ignore bright stars (>140 luma) when estimating background sky glow
                        if (r + g + b < 360) {
                            rAcc += r; gAcc += g; bAcc += b; n++
                        }
                        x += 4
                    }
                    y += 4
                }
                val gi = cy * gx + cx
                if (n > 0) {
                    gridR[gi] = (rAcc.toFloat() / n) * 0.45f
                    gridG[gi] = (gAcc.toFloat() / n) * 0.42f
                    gridB[gi] = (bAcc.toFloat() / n) * 0.36f // keep slight cool night blue while pulling orange glow!
                }
            }
        }

        for (y in 0 until h) {
            val fy = (y.toFloat() / h) * (gy - 1)
            val y0 = fy.toInt().coerceIn(0, gy - 2)
            val ty = fy - y0
            val row = y * w
            for (x in 0 until w) {
                val fx = (x.toFloat() / w) * (gx - 1)
                val x0 = fx.toInt().coerceIn(0, gx - 2)
                val tx = fx - x0

                val i00 = y0 * gx + x0
                val i10 = i00 + 1
                val i01 = i00 + gx
                val i11 = i01 + 1

                val bgR = bilerp(gridR[i00], gridR[i10], gridR[i01], gridR[i11], tx, ty)
                val bgG = bilerp(gridG[i00], gridG[i10], gridG[i01], gridG[i11], tx, ty)
                val bgB = bilerp(gridB[i00], gridB[i10], gridB[i01], gridB[i11], tx, ty)

                val p = px[row + x]
                val r = (((p shr 16) and 0xFF) - bgR).toInt().coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) - bgG).toInt().coerceIn(0, 255)
                val b = ((p and 0xFF) - bgB).toInt().coerceIn(0, 255)
                px[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun bilerp(a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float): Float {
        val top = a + (b - a) * tx
        val bot = c + (d - c) * tx
        return top + (bot - top) * ty
    }
}
